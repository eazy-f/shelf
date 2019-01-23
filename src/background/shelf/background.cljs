(ns shelf.background
  (:require-macros [chromex.support :refer [runonce]]
                   [cljs.core.async :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.bookmarks :as bookmarks]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.storage :as storage]
            [chromex.protocols :as storage-proto]
            [cljs.core.async :refer [<! >! chan promise-chan pipe to-chan]]
            [clojure.string :as str]
            [clojure.set :as set]
            [cljs.test :refer-macros [deftest is async]]))

(def ^:static app-name "shelf")
(def ^:static client-id-storage-key "client-id")
(def ^:static domain-storage-key "domain")

(defprotocol Bookmark
  (b-id [this])
  (b-parent [this])
  (b-title [this])
  (b-url [this])
  (b-type [this]))

(deftype BrowserBookmark [js-bookmark]
  Bookmark
   (b-id     [this] (.-id js-bookmark))
   (b-title  [this] (.-title js-bookmark))
   (b-url    [this] (.-url js-bookmark))
   (b-type   [this] (.-type js-bookmark)))

(deftype TestBookmark [tuple]
  Bookmark
   (b-id     [this] (nth tuple 1))
   (b-title  [this] (nth tuple 2))
   (b-url    [this] nil)
   (b-type   [this] (if (= (first tuple) :folder) "folder" "bookmark")))

(defprotocol BookmarkStorage
  (peer-list [this])
  (peer-load [this peer-id]))

(defprotocol StatefulStorage
  (get-state [this]))

(deftype MemoryStorage [handle]
  BookmarkStorage
  (peer-list [this]
    [])
  (peer-load [this peer-id]
    {})
  StatefulStorage
  (get-state [this]
    {}))

(defprotocol BookmarkTree
  (get-tree [this])
  #_(comment (add-bookmark [this bookmark])
  (add-folder [this name])))

(deftype BrowserBookmarks []
  BookmarkTree
  (get-tree [this]
    (bookmarks/get-tree)))

(deftype MemoryBookmarks [chan]
  BookmarkTree
  (get-tree [this]
    []))

(defn in-memory-bookmarks [state]
  (MemoryBookmarks. (chan)))

(defn- file-client-id [filename]
  (first (str/split filename #"\.")))

(defn read-bookmarks-file [domain file-name]
  (go
    (let [command (clj->js {:op "load" :args {:name file-name} :domain domain})
          reply (<! (runtime/send-native-message app-name command))
          content (.-reply (first reply))]
      [(file-client-id file-name)
       (js->clj content :keywordize-keys true)])))

(deftype LocalFileStorage [domain]
  BookmarkStorage
  (peer-list [this]
    (go
      (let [list-command (clj->js {:op "list" :domain domain})
            file-list-reply (<! (runtime/send-native-message app-name list-command))]
        (.-names (.-reply (first file-list-reply))))))
  (peer-load [this filename]
    (read-bookmarks-file domain filename)))

(defn in-memory-storage
  ([] (in-memory-storage [{}]))
  ([state] (chan)))

(defn get-or-generate [key prefix]
  (go
    (let [local (storage/get-local)
          values (<! (storage-proto/get local key))]
      (if-let [value (aget (first (first values)) key)]
        value
        (let [new-value (str prefix "-" (rand-int (* 256 256 256 256)))]
          (storage-proto/set local (clj->js {key new-value}))
          new-value)))))

(defn get-client-id []
  (get-or-generate client-id-storage-key "bookmarks"))

(defn get-domain []
  (get-or-generate domain-storage-key "shelf-domain"))

(defn local-file-storage []
  (go
    (let [domain (<! (get-domain))]
      (LocalFileStorage. domain))))

(defn load-saved-bookmarks [storage]
  (go
    (->>
     (map #(peer-load storage %) (<! (peer-list storage)))
     (cljs.core.async/merge)
     (cljs.core.async/into (hash-map))
     ; FIXME: do something about this unwrapping
     (<!))))

(defn build-bookmark [parent-id ^Bookmark bookmark]
  {:id        (b-id bookmark)
   :parent-id parent-id
   :title     (b-title bookmark)
   :url       (b-url bookmark)
   :type      (b-type bookmark)})

(defn fold-bookmark-tree-backward
  [tree get-children tjoin tmap]
  (tjoin
   (tmap tree)
   (map
    #(fold-bookmark-tree-backward % get-children tjoin tmap)
    (get-children tree))))

; not tail recursive
(defn- fold-bookmark-tree-
  ([tree get-children folder acc] (fold-bookmark-tree- tree get-children folder acc nil))
  ([tree get-children folder acc parent]
   (reduce
    (fn [acc branch] (fold-bookmark-tree- branch get-children folder acc tree))
    (if tree (folder acc parent tree) acc)
    (get-children tree))))

(defn fold-bookmark-tree
  ([tree] (fold-bookmark-tree tree #(.-children %) #(BrowserBookmark. %)))
  ([tree get-children new-bookmark]
   (fold-bookmark-tree-
    tree
    get-children
    (fn [acc parent node]
      (let [bookmark (build-bookmark
                      (when parent (:id (new-bookmark parent)))
                      (new-bookmark node))]
        (cons bookmark acc)))
    ())))

(defn load-browser-bookmarks [browser-runtime]
  (go
    (->> (<! (get-tree browser-runtime))
         (first)
         (first)
         (fold-bookmark-tree))))

(defn show-bookmarks []
  (go
    (doseq [b (<! (load-browser-bookmarks (BrowserBookmarks.)))] (print b))))

(defn log-added [bookmark]
  {:added (:id bookmark)})

(defn build-fresh-log [bookmarks]
  (map log-added bookmarks))

(defn save-bookmarks [version bookmarks log]
  (go
    (let [domain (<! (get-domain))
          args {:name (<! (get-client-id))
                :version version
                :bookmarks (into () bookmarks)
                :log (into () log)}
          message (clj->js {:op "save" :args args :domain domain})]
      (-> (<! (runtime/send-native-message app-name message))
          (first)
          (.-result)
          (= "success")
          (assert "failed to save bookmarks")))))

(defn show [chann]
  (go-loop [c chann]
    (print (<! c))
    (recur c)))

(defn- flat-bookmarks
  ([bookmarks]
   (into (hash-map) (map #(vector (:id %) %) bookmarks))))

(defn- calculate-own-changeset [disk-state browser-bookmarks-list]
  (let [saved-bookmarks-tree (:bookmarks disk-state)
        saved-bookmarks (flat-bookmarks saved-bookmarks-tree)
        browser-bookmarks (flat-bookmarks browser-bookmarks-list)
        saved-bookmark-ids (set (keys saved-bookmarks))
        browser-bookmark-ids (set (keys browser-bookmarks))
        joint (set/intersection browser-bookmark-ids saved-bookmark-ids)
        added (set/difference browser-bookmark-ids joint)
        deleted (set/difference saved-bookmark-ids joint)
        changed (filter #(not= (get saved-bookmarks %2)
                               (get browser-bookmarks %2))
                        joint)]
    (apply concat
           (map map
                [#(hash-map :added %) #(hash-map :deleted %) #(hash-map :changed %)]
                [added deleted changed]))))

(defn- log-append [existing & logs]
  (into () (apply concat (cons existing logs))))

(defn- calculate-peers-changeset [peers log existing]
  (let [import-entries (filter :import log)
        imported (into (hash-map) (map (fn [i] [(:import i) (:to i)]) import-entries))
        peer-changeset (fn [peer]
                         (let [[id content] peer]
                           (if-not (get imported id)
                             (list {:import id}))))]
    (apply concat
           (map peer-changeset peers))))

(defn refresh-within [client-id storage browser-runtime]
  (go
    (let [saved (<! (load-saved-bookmarks storage))
          existing (<! (load-browser-bookmarks browser-runtime))
          own-saved (get saved client-id)
          peers (dissoc saved client-id)
          saved-log (:log own-saved)
          own-version (:version own-saved)
          own-changelog (calculate-own-changeset own-saved existing)
          peers-changelog (calculate-peers-changeset peers saved-log existing)
          new-version (if (not-empty own-changelog) (inc own-version) own-version)]
      (->> (log-append own-changelog peers-changelog)
           (map #(assoc % :version new-version))
           (log-append saved-log)
           (save-bookmarks new-version existing)))))

(defn refresh []
  "Sync browser bookmarks with peers and own storage"
  (go
    (let [local-storage (<! (local-file-storage))
          runtime (BrowserBookmarks.)
          client-id (<! (get-client-id))]
      (refresh-within client-id local-storage runtime))))

(def test-tree-iterators [#(drop 3 %) #(TestBookmark. %)])

(defn apply-change
  [tree-chan change]
  (cond
   (or (:import change) (:add change))
   (go (>! tree-chan {:insert (:name change)
                      :type (:type change)
                      :parent-id (-> :parent-id change <!)
                      :reply (:id-chan change)}))
   (:delete change)
   (go (>! tree-chan {:delete (:id change)}))))

(defn ids-to-tree
  [ids nodes]
  (into
   ()
   (map
    (fn [[id children]]
      (let [node (nodes id)]
        (concat
         (list (keyword (:type node)) id (:title node))
         (ids-to-tree children nodes))))
    ids)))

(defn unfold-bookmark-tree
  [nodes]
  (let [node-id-path (fn self [[id node] acc]
                       (if-let [parent-id (:parent-id node)]
                         (self [parent-id (nodes parent-id)] (conj acc id))
                         (reverse (conj acc id))))
        parents (reduce conj #{} (keep (comp :parent-id second) nodes))
        leaves (remove (comp parents first) nodes)]
    (-> (reduce #(assoc-in %1 (node-id-path %2 []) {}) {} (reverse leaves))
        (ids-to-tree nodes)
        (first))))

(defn create-tree-builder [init-tree]
  (let [cmd-chan (chan)]
    (go-loop [nodes (flat-bookmarks
                     (apply fold-bookmark-tree init-tree test-tree-iterators))]
      (let [cmd (<! cmd-chan)]
        (when-let [client (:get-tree cmd)]
          (>! client {:wrapper (unfold-bookmark-tree nodes)})
          (recur nodes))
        (when-let [name (:insert cmd)]
          (let [client (:reply cmd)
                base (select-keys cmd [:parent-id :type])
                new-id (inc (reduce max 0 (keys nodes)))
                new-node (assoc base :id new-id :title name)]
            (>! client new-id)
            (recur (assoc nodes new-id new-node))))))
    cmd-chan))

(defn apply-log
  [rlog self]
  (let [tree-chan (create-tree-builder (:tree self))
        result (chan)
        log (reverse rlog)]
    (doall (map (partial apply-change tree-chan) log))
    (go
      (<! (cljs.core.async/map identity (keep :id-chan log)))
      (>! tree-chan {:get-tree result})
      (<! result))))

(defn find-trunk
  [log name]
  (let [is-peer-import #(and (:import %) (= (:name %) name))]
    (reduce
     (fn [candidate operation]
       (if (= (:import operation) name)
         operation
         (if (and candidate
                  (= (:delete operation) (:id candidate)))
           nil
           candidate)))
     nil
     (reverse log))))

(defn ensure-trunk-exists
  [log tree peer-name]
  (let [parent-chan (promise-chan)
        import-entry {:import peer-name
                      :name peer-name
                      :parent-id parent-chan
                      :type :folder
                      :id-chan (promise-chan)}]
    (as-> log v
        (find-trunk v peer-name)
        (if-let [trunk v]
          (conj log {:delete (:id trunk)})
          log)
        (let [root-id (-> :tree tree (nth 1))]
          (pipe (to-chan (list root-id)) parent-chan)
          (conj v import-entry)))))

(defn remove-imported-bookmarks
  "remove all children of a given trunk"
  [trunk tree]
  nil)

(defn peer-import
  "import peer bookmarks to the main tree and update the log accordingly"
  [[log0 tree] peer]
  (let [name (:name peer)
        peer-tree (:tree peer)
        log (ensure-trunk-exists log0 tree name)
        trunk (find-trunk log name)
        existing-trunk? :version
        add-bookmark (fn [log parent node]
                       (let [node-type (nth node 0)
                             node-name (nth node 2)
                             old-parent-id (nth parent 1)
                             original-id (nth node 1)
                             parent-find (if parent
                                           #(= old-parent-id (:original-id %))
                                           #(= name (:import %)))
                             parent-id (->> log
                                            (drop-while (complement parent-find))
                                            first
                                            :id-chan)
                             change {:add node-name
                                     :name node-name
                                     :parent-id parent-id
                                     :original-id original-id
                                     :type node-type
                                     :id-chan (promise-chan)}]
                         (conj log change)))
        [get-children _] test-tree-iterators]
    (println "log" log)
    (as-> log v
      (concat (if (existing-trunk? trunk)
                (remove-imported-bookmarks trunk tree))
              v)
      (fold-bookmark-tree- peer-tree get-children add-bookmark v)
      [v tree])))

(runonce (refresh))

(defn clear-ids
  [tree]
  (fold-bookmark-tree-backward
   tree
   (first test-tree-iterators)
   concat
   #(->> %
         (map-indexed (fn [i val] (if (not= i 1) val)))
         (take 3))))

(deftest peer-import-test
  (let [own-tree {:name "root"
                  :tree '(:folder 1 "root")}
        peer-one-tree '(:folder 1 "root"
                                (:bookmark 2 "link1")
                                (:bookmark 3 "link2"))
        peer-two-tree '(:folder 1 "root"
                                (:bookmark 2 "link1"))
        iterators test-tree-iterators
        peer (fn [name tree] {:name name :tree tree})
        peer-one-ftree (peer "one" peer-one-tree)
        peer-two-ftree (peer "two" peer-two-tree)]
    (async done
     (go
       (is
        (=
         (as-> (reduce peer-import [() own-tree] (list peer-one-ftree peer-two-ftree)) v
           (apply apply-log v)
           (<! v)
           (:wrapper v)
           (clear-ids v))
         (clear-ids
          (concat
           (:tree own-tree)
           (list
            `(:folder nil "one" ~peer-one-tree)
            `(:folder nil "two" ~peer-two-tree))))))
       (let [name "one"
             original {:import name :id 1}
             includes? #(= (select-keys %1 (keys %2)) %2)]
         (is
          (every? true?
           (map includes?
                (ensure-trunk-exists (list original) own-tree name)
                (into () [original {:delete (:id original)} {:import name}])))))
       (done)))))

(deftest import-idempotent-test
  (async
   done
   (go
     (let [bookmarks-state {}
           bookmark-tree (in-memory-bookmarks bookmarks-state)
           storage-state bookmarks-state
           storage (in-memory-storage storage-state)]
       (is
        (=
         storage-state
         (let [client-id 0]
           (refresh-within client-id storage bookmark-tree)
           (get-state storage)))))
     (done))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (println "Success!")
    (println "FAIL")))

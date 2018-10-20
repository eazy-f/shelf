(ns shelf.background
  (:require-macros [chromex.support :refer [runonce]]
                   [cljs.core.async :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.bookmarks :as bookmarks]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.storage :as storage]
            [chromex.protocols :as storage-proto]
            [cljs.core.async :refer [<! >! chan]]
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

(defn get-or-generate [key prefix]
  (go
    (let [local (storage/get-local)
          values (<! (storage-proto/get local key))]
      (if-let [value (aget (first (first values)) key)]
        value
        (let [new-value (str prefix "-" (rand-int (* 256 256 256 256)))]
          (storage-proto/set local (clj->js {key new-value}))
          new-value)))))

(defn get-domain []
  (get-or-generate domain-storage-key "shelf-domain"))

(defn get-client-id []
  (get-or-generate client-id-storage-key "bookmarks"))

(defn- file-client-id [filename]
  (first (str/split filename #"\.")))

(defn read-bookmarks-file [domain file-name]
  (go
    (let [command (clj->js {:op "load" :args {:name file-name} :domain domain})
          reply (<! (runtime/send-native-message app-name command))
          content (.-reply (first reply))]
      [(file-client-id file-name)
       (js->clj content :keywordize-keys true)])))

(defn load-saved-bookmarks []
  (go
    (let [domain (<! (get-domain))
          list-command (clj->js {:op "list" :domain domain})
          file-list-reply (<! (runtime/send-native-message app-name list-command))
          file-list (.-names (.-reply (first file-list-reply)))]
      (->>
       (map #(read-bookmarks-file domain %) file-list)
       (cljs.core.async/merge)
       (cljs.core.async/into (hash-map))
       ; FIXME: do something about this unwrapping
       (<!)))))

(defn build-bookmark [parent-id ^Bookmark bookmark]
  {:id        (b-id bookmark)
   :parent-id parent-id
   :title     (b-title bookmark)
   :url       (b-url bookmark)
   :type      (b-type bookmark)})

(defn fold-bookmark-tree
  ([tree] (fold-bookmark-tree tree [#(.-children %) #(BrowserBookmark. %)]))
  ([tree iterators] (fold-bookmark-tree tree iterators () () ()))
  ([tree [get-children new-bookmark :as iterators] parents stack acc]
   (if-let [head (first tree)]
     (let [parent-id (first parents)
           bookmark (build-bookmark parent-id (new-bookmark head))]
       (fold-bookmark-tree
        (get-children head)
        iterators
        (cons (:id bookmark) parents)
        (cons (rest tree) stack)
        (cons bookmark acc)))
     (if-let [branch (first stack)]
       (fold-bookmark-tree branch iterators (rest parents) (rest stack) acc)
       acc))))

(defn load-browser-bookmarks []
  (go
    (->> (<! (bookmarks/get-tree))
         (first)
         (fold-bookmark-tree))))

(defn show-bookmarks []
  (go
    (doseq [b (<! (load-browser-bookmarks))] (print b))))

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

(defn refresh []
  (go
    (let [saved (<! (load-saved-bookmarks))
          existing (<! (load-browser-bookmarks))
          client-id (<! (get-client-id))
          own-saved (get saved client-id)
          peers (dissoc saved client-id)
          saved-log (:log own-saved)
          own-version (:version own-saved)
          own-changelog (calculate-own-changeset own-saved existing)
          peers-changelog (calculate-peers-changeset peers saved-log existing)
          new-version (if (not-empty own-changelog) (inc own-version) own-version)]
      (println peers-changelog)
      (->> (log-append own-changelog peers-changelog)
           (map #(assoc % :version new-version))
           (log-append saved-log)
           (save-bookmarks new-version existing)))))

(def test-tree-iterators [#(drop 3 %) #(TestBookmark. %)])

(defn apply-change
  [tree-chan change]
  (let []
    (cond
      (or (:import change) (:add change))
      (go (>! tree-chan {:insert (:name change) :reply (:id-chan change)}))
      (:delete change)
      (go (>! tree-chan {:delete (:id change)})))))

(defn ids-to-tree
  [id-col nodes]
  (into
   ()
   (map
    (fn [[id children]]
      (concat
       (list :folder id ((comp nodes :name) id))
       (ids-to-tree children nodes)))
    id-col)))

(defn unfold-bookmark-tree
  [nodes]
  (let [node-id-path (fn [[id node] acc]
                       (if-let [parent (:parent-id node)]
                         (recur (parent nodes) (conj acc parent))
                         (conj acc id)))]
    (-> (reduce #(assoc-in %1 (node-id-path %2 []) {}) {} nodes)
        (ids-to-tree nodes)
        (first))))

(defn create-tree-builder [init-tree]
  (let [cmd-chan (chan)]
    (go-loop [nodes (flat-bookmarks
                     (fold-bookmark-tree (list init-tree) test-tree-iterators))]
      (let [cmd (<! cmd-chan)]
        (if-let [client (:get-tree cmd)]
          (>! client {:wrapper (unfold-bookmark-tree nodes)}))
        (recur nodes)))
    cmd-chan))

(defn apply-log
  [log self]
  (let [tree-chan (create-tree-builder (:tree self))
        result (chan)]
    (for [change log] (apply-change tree-chan change))
    (go
      (>! tree-chan {:get-tree result})
      (<! result))))

(defn find-trunk
  [log name]
  (let [is-peer-import #(and (:import %) (= (:name %) name))]
    (first (filter is-peer-import log))))

(defn ensure-trunk-exists
  [log tree peer-name]
  (if-not (find-trunk log peer-name)
    (conj log {:import peer-name :name peer-name})
    log))

(defn peer-import
  [[log0 tree] peer]
  (let [name (:name peer)
        log (ensure-trunk-exists log0 tree name)
        trunk (find-trunk log name)]
    [log tree]))

(runonce (refresh))

(deftest peer-import-test
  (let [own-tree {:name "root"
                  :tree '(:folder 1 "root")}
        peer-one-tree '(:folder 1 "root"
                                (:bookmark 2 "link1")
                                (:bookmark 3 "link2"))
        peer-two-tree '(:folder 1 "root"
                                (:bookmark 2 "link1"))
        iterators test-tree-iterators
        peer (fn [name tree]
               {:name name
                :tree (fold-bookmark-tree (list tree) iterators)})
        peer-one-ftree (peer "one" peer-one-tree)
        peer-two-ftree (peer "two" peer-two-tree)]
    (async done
     (go
       (is
        (=
         (<!
          (apply
           apply-log
           (reduce peer-import [() own-tree] (list peer-one-ftree peer-two-ftree))))
         (conj
          (:tree own-tree)
          `(:tree nil "one" ~peer-one-tree)
          `(:tree nil "two" ~peer-two-tree))))
       (done)))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (println "Success!")
    (println "FAIL")))

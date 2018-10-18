(ns shelf.background
  (:require-macros [chromex.support :refer [runonce]]
                   [cljs.core.async :refer [go]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.bookmarks :as bookmarks]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.storage :as storage]
            [chromex.protocols :as storage-proto]
            [cljs.core.async :refer [<!]]
            [clojure.string :as str]
            [clojure.set :as set]
            [cljs.test :refer-macros [deftest is]]))

(def ^:static app-name "shelf")
(def ^:static client-id-storage-key "client-id")
(def ^:static domain-storage-key "domain")

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

(defn fold-bookmark-tree
  ([tree] (fold-bookmark-tree tree [#(.-children %) first rest]))
  ([tree iterators] (fold-bookmark-tree tree [#(.-children %) first rest] () ()))
  ([tree [get-children get-first get-rest :as iterators] stack acc]
   (if-let [head (get-first tree)]
     (fold-bookmark-tree
      (get-children head)
      iterators
      (cons (get-rest tree) stack)
      (cons head acc))
     (if-let [branch (get-first stack)]
       (fold-bookmark-tree branch iterators (rest stack) acc)
       acc))))

(defn build-bookmark [browser-bookmark]
  {:id (.-id browser-bookmark)
   :parent-id (.-parentId browser-bookmark)
   :title (.-title browser-bookmark)
   :url (.-url browser-bookmark)
   :type (.-type browser-bookmark)})

(defn load-browser-bookmarks []
  (go
    (->> (<! (bookmarks/get-tree))
         (first)
         (fold-bookmark-tree)
         (map build-bookmark))))

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

(defn show [chan]
  (go
    (print (<! chan))))

(defn- flat-bookmarks
  ([bookmarks] (flat-bookmarks bookmarks :id))
  ([bookmarks get-id]
   (into (hash-map) (map #(vector (get-id %) %) bookmarks))))

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
      (->> (log-append saved-log own-changelog peers-changelog)
           (map #(assoc % :version new-version))
           (log-append saved-log)
           (save-bookmarks new-version existing)))))

(defn apply-log
  [log tree]
  tree)

(defn peer-import
  [[log tree] peer]
  [log tree])

(runonce (refresh))

(deftest peer-import-test
  (let [own-tree {:name "root" :tree ()}
        peer-one-tree '(:folder 1 "root"
                                (:bookmarks 2 "link1")
                                (:bookmarks 3 "link2"))
        peer-two-tree '(:folder 1 "root"
                                (:bookmark 2 "link1"))
        iterators [#(drop 3 %) first rest]
        peer (fn [name tree]
               {:name name
                :tree (fold-bookmark-tree tree iterators)})
        peer-one-ftree (peer "one" peer-one-tree)
        peer-two-ftree (peer "two" peer-two-tree)]
    (is
     (=
      (apply
       apply-log
       (reduce peer-import [() own-tree] (list peer-one-ftree peer-two-ftree)))
      (conj
       (:tree own-tree)
       `(:tree nil "one" ~peer-one-tree)
       `(:tree nil "two" ~peer-two-tree))))))

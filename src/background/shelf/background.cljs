(ns shelf.background
  (:require-macros [chromex.support :refer [runonce]]
                   [cljs.core.async :refer [go]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.bookmarks :as bookmarks]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.storage :as storage]
            [chromex.protocols :as storage-proto]
            [cljs.core.async :refer [<!]]))

(def *app-name* "shelf")
(def *client-id-storage-key* "client-id")
(def *domain-storage-key* "domain")

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
  (get-or-generate *domain-storage-key* "shelf-domain"))

(defn get-client-id []
  (get-or-generate *client-id-storage-key* "bookmarks"))

(defn read-bookmarks-file [domain file-name]
  (go
    (let [command (clj->js {:op "load" :args {:name file-name} :domain domain})
          reply (<! (runtime/send-native-message *app-name* command))
          content (.-reply (first reply))]
      (js->clj content))))

(defn load-saved-bookmarks []
  (go
    (let [domain (<! (get-domain))
          list-command (clj->js {:op "list" :domain domain})
          file-list-reply (<! (runtime/send-native-message *app-name* list-command))
          file-list (.-names (.-reply (first file-list-reply)))]
      (->>
       (map #(read-bookmarks-file domain %) file-list)
       (cljs.core.async/merge)
       (cljs.core.async/into ())
       ; FIXME: do something about this unwrapping
       (<!)))))

(defn fold-bookmark-tree- [tree stack acc]
  (if-let [head (first tree)]
    (fold-bookmark-tree- (.-children head) (cons (rest tree) stack) (cons head acc))
    (if-let [branch (first stack)]
      (fold-bookmark-tree- branch (rest stack) acc)
      acc)))

(defn fold-bookmark-tree [tree]
  (fold-bookmark-tree- tree () ()))

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

(defn save-bookmarks [bookmarks log]
  (go
    (let [domain (<! (get-domain))
          args {:name (<! (get-client-id))
                :bookmarks (into () bookmarks)
                :log (into () log)}
          message (clj->js {:op "save" :args args :domain domain})]
      (-> (<! (runtime/send-native-message *app-name* message))
          (first)
          (.-result)
          (= "success")
          (assert "failed to save bookmarks")))))

(defn show [chan]
  (go
    (print (<! chan))))

(defn refresh []
  (go
   (let [saved (<! (load-saved-bookmarks))
         existing (<! (load-browser-bookmarks))]
     (save-bookmarks existing (build-fresh-log existing)))))
    
(runonce (refresh))

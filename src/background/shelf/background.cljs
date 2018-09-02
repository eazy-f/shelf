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

(defn load-saved-bookmarks []
  (let [list-command (clj->js {:op "list"})
        store-chan (runtime/send-native-message *app-name* list-command)]
    (go
      (->> (<! store-chan)
           (first)
           (.-names)
           (apply js/console.log)))))

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

(defn get-client-id []
  (go
    (let [local (storage/get-local)
          values (<! (storage-proto/get local *client-id-storage-key*))]
      (if-let [id (aget (first (first values)) *client-id-storage-key*)]
        id
        (let [new-id (str "bookmarks-" (rand-int (* 256 256 256 256)))]
          (storage-proto/set local (clj->js {*client-id-storage-key* new-id}))
          new-id)))))

(defn save-bookmarks [bookmarks log]
  (go
    (let [args {:name (<! (get-client-id))
                :bookmarks (into () bookmarks)
                :log (into () log)}
          message (clj->js {:op "save" :args args})]
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

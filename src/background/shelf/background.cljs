(ns shelf.background
  (:require-macros [chromex.support :refer [runonce]]
                   [cljs.core.async :refer [go]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.bookmarks :as bookmarks]
            [chromex.ext.runtime :as runtime]
            [cljs.core.async :refer [<!]]))

(defn load-saved-bookmarks []
  (let [store-app "shelf"
        list-command (clj->js {:op "list"})
        store-chan (runtime/send-native-message store-app list-command)]
    (go
      (->> (<! store-chan)
           (first)
           (.-names)
           (apply js/console.log)))))

(runonce
 (log "Hi there")
 (go
   (let [saved (<! (load-saved-bookmarks))
         root (<! (bookmarks/get-tree))]
     (-> root
         (first)
         (first)
         (.-id)
         (js/console.log)))))


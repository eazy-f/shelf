(ns shelf.background.storage
  (:require [cljs.core.async :refer [<! >! close! chan promise-chan pipe to-chan go]]
            [clojure.string :as str]
            [chromex.ext.runtime :as runtime]
            firebase))

(def ^:static app-name "shelf")

(defprotocol BookmarkStorage
  (peer-list [this])
  (peer-load [this peer-id])
  (peer-save [this peer-id version bookmarks log]))

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

(defn- file-client-id [filename]
  (first (str/split filename #"\.")))

(defn read-bookmarks-file [domain filename]
  (go
    (let [command (clj->js {:op "load" :args {:name filename} :domain domain})
          reply (<! (runtime/send-native-message app-name command))
          content (.-reply (first reply))]
      [(file-client-id filename)
       (js->clj content :keywordize-keys true)])))

(defn save-bookmarks [domain client-id version bookmarks log]
  (go
    (let [args {:name client-id
                :version version
                :bookmarks (into () bookmarks)
                :log (into () log)}
          message (clj->js {:op "save" :args args :domain domain})]
      (-> (<! (runtime/send-native-message app-name message))
          (first)
          (.-result)
          (= "success")
          (assert "failed to save bookmarks")))))

(deftype LocalFileStorage [domain]
  BookmarkStorage
  (peer-list [this]
    (go
      (let [list-command (clj->js {:op "list" :domain domain})
            file-list-reply (<! (runtime/send-native-message app-name list-command))]
        (.-names (.-reply (first file-list-reply))))))
  (peer-load [this filename]
    (read-bookmarks-file domain filename))
  (peer-save [this filename version bookmarks log]
    (save-bookmarks domain filename version bookmarks log)))

(deftype FirebaseBookmarkStorage [domain app]
  BookmarkStorage
  (peer-list [this]
    (let [result (promise-chan)
          storage (.storage app)
          folder (.ref storage "shelf-bookmarks")]
      (.then
       (.list folder)
       #(go (->> %1 (.-items) (map (fn [ref] (.-name ref))) (>! promise-chan)))
       #(close! promise-chan))))
  (peer-load [this filename]
    nil)
  (peer-save [this filename version bookmarks log]
    nil))

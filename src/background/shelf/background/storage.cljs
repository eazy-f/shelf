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

(defn- firebase-folder-name [domain]
  (str "shelf-" domain))

(deftype FirebaseBookmarkStorage [domain app]
  BookmarkStorage
  (peer-list [this]
    (let [result (promise-chan)
          storage (.storage app)
          folder (.ref storage (firebase-folder-name domain))]
      (.then
       (.list folder)
       #(go (->> %1 (.-items) (map (fn [ref] (.-name ref))) (>! result)))
       #(close! promise-chan))
      result))
  (peer-load [this filename]
    (let [result (promise-chan)
          storage (.storage app)
          folder (.ref storage (firebase-folder-name domain))
          file (.child folder filename)]
      (.then
       (.getDownloadURL file)
       #(.then
         (js/fetch %1)
         (comp (fn [res] (go (>! result res))) js->clj js/JSON.parse)
         (fn [_e] (close! result)))
       #(close! promise-chan))
      result))
  (peer-save [this filename version bookmarks log]
    (let [result (promise-chan)
          storage (.storage app)
          folder (.ref storage (firebase-folder-name domain))
          file (.child folder filename)
          content "{}"]
      (.then
       (.putString file content)
       #(go (>! result true))
       #(close! promise-chan))
      result)))

(defn get-configured-storage [domain config]
  "channel with BookmarkStorage object for Firebase"
  (println "configuring for:" domain config)
  (let [result (promise-chan)
        apikey (config "apikey")
        bucket (config "bucket")
        username (config "username")
        password (config "password")
        app (try
              (firebase/initializeApp #js{:apiKey apikey
                                          :storageBucket bucket}
                                      app-name)
              (catch js/Object e (println e) (firebase/app app-name)))]
    (.then
     (.signInWithEmailAndPassword (.auth app) username password)
     #(go (>! result (FirebaseBookmarkStorage. domain app)))
     #(close! result))
    result))

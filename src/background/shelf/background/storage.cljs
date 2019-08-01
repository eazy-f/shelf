(ns shelf.background.storage
  (:require [cljs.core.async :refer [<! >! close! chan promise-chan pipe to-chan go]]
            [clojure.string :as str]
            [chromex.ext.runtime :as runtime]
            firebase
            [shelf.background.crypto :refer [encrypt decrypt
                                             buffer-to-hex hex-to-buffer
                                             buffer-to-text text-to-buffer]]))

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

(defn peer-snapshot-object [client-id version bookmarks log]
  {:name client-id
   :version version
   :bookmarks (into () bookmarks)
   :log (into () log)})

(defn save-bookmarks [domain client-id version bookmarks log]
  (go
    (let [args (peer-snapshot-object client-id version bookmarks log)
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

(deftype FirebaseBookmarkStorage [domain app stg-key]
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
    (let [result (chan 1)
          iv-promise (promise-chan)
          storage (.storage app)
          folder (.ref storage (firebase-folder-name domain))
          file (.child folder filename)]
      (.then
       (.getMetadata file)
       #(go
          (->> %1
              (.-customMetadata)
              (.-iv)
              (hex-to-buffer)
              (>! iv-promise)))
       #(close! iv-promise))
      (.then
       (.getDownloadURL file)
       #(.then
         (js/fetch %1 #js{:mode "no-cors"})
         (fn [response]
           (.then
            (.arrayBuffer response)
            (fn [ciphertext]
              (go
                (as-> (<! iv-promise) v
                  (decrypt (hex-to-buffer stg-key) v ciphertext)
                  (<! v)
                  (buffer-to-text v)
                  (.parse js/JSON v)
                  (js->clj v :keywordize-keys true)
                  (>! result [filename v]))
                (close! result)))
            (fn [_e] (close! result))))
         (fn [_e] (close! result)))
       #(close! result))
      result))
  (peer-save [this filename version bookmarks log]
    (let [result (promise-chan)
          storage (.storage app)
          folder (.ref storage (firebase-folder-name domain))
          file (.child folder filename)
          snapshot (peer-snapshot-object filename version bookmarks log)
          snapshot-plain (text-to-buffer (.stringify js/JSON (clj->js snapshot)))
          metadata (fn [iv] #js{:customMetadata #js{:iv (buffer-to-hex iv)}})]
      (go
        (if-some [[iv content] (<! (encrypt (hex-to-buffer stg-key) snapshot-plain))]
          (.then
           (.put file content (metadata iv))
           #(go (>! result true))
           #(close! promise-chan))
          (close! result)))
      result)))

(defn get-configured-storage [domain config]
  "channel with BookmarkStorage object for Firebase"
  (let [result (promise-chan)
        apikey (config "apikey")
        bucket (config "bucket")
        username (config "username")
        password (config "password")
        stg-key  (config "stg-key")
        app (try
              (firebase/initializeApp #js{:apiKey apikey
                                          :storageBucket bucket}
                                      app-name)
              (catch js/Object e (println e) (firebase/app app-name)))]
    (.then
     (.signInWithEmailAndPassword (.auth app) username password)
     #(go (>! result (FirebaseBookmarkStorage. domain app stg-key)))
     #(close! result))
    result))

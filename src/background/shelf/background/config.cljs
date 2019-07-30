(ns shelf.background.config
  (:require-macros [cljs.core.async :refer [go-loop go]])
  (:require [cljs.core.async :refer [chan >! <! mult promise-chan]]
            [clojure.string]
            [chromex.protocols.chrome-storage-area :as browser-storage]))

(def EMPTYSTATE {:type "empty"})
(def DEFAULTCONFIG
  {:username "user@user.com"
   :password "pass"})

(defn- decrypt [stored]
  (let [crypto js/window.crypto])
  (.parse js/JSON (clojure.string/reverse stored)))

(defn- text-to-buffer [text]
  (.encode (js/TextEncoder.) text))

(defn- hex-to-buffer [string]
  (let [buffer-length (quot (count string) 2)
        pairs (partition 2 string)
        array (js/Uint8Array. buffer-length)]
    (dorun
     (map-indexed
      (fn [i [h l]]
        (aset array i (js/parseInt (str h l) 16)))
      pairs))
    array))

(defn- buffer-to-hex [buffer]
  (apply
   str
   (for [oct (array-seq buffer)] (.padStart (.toString oct 16) 2 "0"))))

(defn- encrypt [config pin salt]
  (let [iv (.getRandomValues js/crypto (js/Uint8Array. 16))
        plaintext (.stringify js/JSON (clj->js config))
        cipher #js{:name "AES-CBC" :iv iv}
        encoded-pin (text-to-buffer pin)
        master-promise (promise-chan)]
  (go
    (.then
     (.importKey
      js/crypto.subtle
      "raw"
      encoded-pin
      "PBKDF2"
      false
      #js["deriveKey"])
     #(go (>! master-promise %1)))
    (let [master-key (<! master-promise)
          key-promise (promise-chan)]
      (.then
       (.deriveKey
        js/crypto.subtle
        #js{:name "PBKDF2"
            :salt salt
            :iterations 100000
            :hash "SHA-512"}
        master-key
        #js{:name "AES-CBC" :length 256}
        true
        #js["encrypt" "decrypt"])
       #(go (>! key-promise %1)))
      (let [encryption-key (<! key-promise)
            ciphertext-promise (promise-chan)]
        (.then
         (.encrypt
          js/crypto.subtle
          cipher
          encryption-key
          (text-to-buffer plaintext))
         #(go (>! ciphertext-promise %1)))
        (let [ciphertext (<! ciphertext-promise)]
          (buffer-to-hex (js/Uint8Array. ciphertext))))))))

(defn- load-stored-state [storage]
  (go
    (if-some [stored (.-config (first (first (<! (browser-storage/get storage)))))]
      (do
        (println "read:" stored)
        {:type "configured"})
      EMPTYSTATE)))

(defn- clear-storage [storage]
  (go (<! (browser-storage/remove storage "config"))))

(defn- generate-pin []
  "1112")

(defn- generate-encryption-key []
  "Donhirch0Ow2")

(defn- generate-salt []
  (.getRandomValues js/crypto (js/Uint8Array. 16)))

(defn- configure-storage [storage]
  (go
    (let [stg-encryption-key (generate-encryption-key)
          pin (generate-pin)
          stored-config (into DEFAULTCONFIG
                              {:type "active"
                               :stg-key stg-encryption-key
                               :pin pin})
          salt (generate-salt)
          encrypted-config (<! (encrypt stored-config pin salt))
          stored-objects {:config encrypted-config :config-salt (buffer-to-hex salt)}]
      ;;FIXME: double check this read
      (when (<! (browser-storage/set storage (clj->js stored-objects)))
        (into stored-config {:pin pin})))))

(defn- try-activate [state pin]
  (if (= pin (:pin state))
    {:type "active" :pin pin}
    state))

(defn- logout [state]
  {:type "configured" :pin (:pin state)})

(defn- handle-command [cmd state storage]
  (go
    (let [[cmd-name & args] cmd
          fresh-start (fn [] (go (<! (clear-storage storage)) (<! (load-stored-state storage))))]
      (case [(:type state) cmd-name]
        ["empty" "configure"] (<! (configure-storage storage))
        ["configured" "activate"] (apply try-activate state args)
        ["configured" "clear"] (<! (fresh-start))
        ["active" "clear"] (<! (fresh-start))
        ["active" "logout"] (logout state)
        state))))

(defn- listen [channels storage]
  (go-loop [state (<! (load-stored-state storage))]
    (when-some [cmd (<! (:commands channels))]
      (let [new-state (<! (handle-command cmd state storage))]
        (>! (:state channels) new-state)
        (recur new-state)))))

(defn load [storage]
  (let [channels {:state (chan) :commands (chan)}]
    (listen channels storage)
    {:state (mult (:state channels)) :commands (:commands channels)}))

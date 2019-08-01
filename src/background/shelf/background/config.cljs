(ns shelf.background.config
  (:require-macros [cljs.core.async :refer [go-loop go]])
  (:require [cljs.core.async :refer [chan >! <! mult promise-chan close!]]
            [clojure.string]
            [chromex.protocols.chrome-storage-area :as browser-storage]))

(def EMPTYSTATE {:type "empty"})
(def DEFAULTCONFIG
  {:username "user@user.com"
   :password "pass"})

(defn- cbc-cipher [iv]
  #js{:name "AES-CBC" :iv iv})

(defn- text-to-buffer [text]
  (.encode (js/TextEncoder.) text))

(defn- buffer-to-text [buffer]
  (.decode (js/TextDecoder.) buffer))

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

(defn- key-to-hex [key]
  (let [decoded (promise-chan)]
  (go
    (.then (.exportKey js/crypto.subtle "raw" key) #(go (>! decoded %1)))
    (buffer-to-hex (js/Uint8Array. (<! decoded))))))

(defn- get-encryption-key [pin salt]
  (let [encoded-pin (text-to-buffer pin)
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
        (<! key-promise)))))

(defn- decrypt [ciphertext pin salt iv]
  (go
    (let [key (<! (get-encryption-key pin salt))
          plaintext-promise (promise-chan)]
      (.then
       (.decrypt
        js/crypto.subtle
        (cbc-cipher iv)
        key
        ciphertext)
       #(go (>! plaintext-promise %1))
       (fn [e] (println e) (close! plaintext-promise)))
      (when-some [plaintext (<! plaintext-promise)]
        (js->clj (.parse js/JSON (buffer-to-text plaintext)))))))

(defn- encrypt [config pin salt iv]
  (let [plaintext (.stringify js/JSON (clj->js config))]
    (go
      (let [encryption-key (<! (get-encryption-key pin salt))
            ciphertext-promise (promise-chan)]
        (.then
         (.encrypt
          js/crypto.subtle
          (cbc-cipher iv)
          encryption-key
          (text-to-buffer plaintext))
         #(go (>! ciphertext-promise %1)))
        (let [ciphertext (<! ciphertext-promise)]
          (buffer-to-hex (js/Uint8Array. ciphertext)))))))

(defn- with-stored [storage worker]
  (go
    (worker (first (first (<! (browser-storage/get storage)))))))

(defn- load-stored-state [storage]
  (with-stored
    storage
    (fn [stored]
      (if-some [config (.-config stored)]
        {:type "configured"}
        EMPTYSTATE))))

(defn- clear-storage [storage]
  (go (<! (browser-storage/remove storage "config"))))

(defn- generate-encryption-key []
  "Donhirch0Ow2")

(defn- generate-salt []
  (.getRandomValues js/crypto (js/Uint8Array. 16)))

(defn- configure-storage [storage configuration]
  (go
    (let [stg-encryption-key (generate-encryption-key)
          configuration-map (js->clj configuration)
          pin (configuration-map "pin")
          stored-config (into (dissoc configuration-map "pin")
                              {"stg-key" stg-encryption-key})
          salt (generate-salt)
          iv (.getRandomValues js/crypto (js/Uint8Array. 16))
          encrypted-config (<! (encrypt stored-config pin salt iv))
          stored-objects {:config encrypted-config
                          :config_salt (buffer-to-hex salt)
                          :config_iv (buffer-to-hex iv)}]
      ;;FIXME: double check this read
      (when (<! (browser-storage/set storage (clj->js stored-objects)))
        (into stored-config {:type "active"})))))

(defn- try-activate [state storage pin]
  (go
    (let [stored (first (first (<! (browser-storage/get storage))))
          config (.-config stored)
          salt (hex-to-buffer (.-config_salt stored))
          iv (hex-to-buffer (.-config_iv stored))]
      (if-let [decrypted (<! (decrypt (hex-to-buffer config) pin salt iv))]
        (into decrypted {:type "active"})
        state))))

(defn- logout [state]
  {:type "configured"})

(defn- handle-command [cmd state storage]
  (go
    (let [[cmd-name & args] cmd
          fresh-start (fn [] (go (<! (clear-storage storage)) (<! (load-stored-state storage))))]
      (case [(:type state) cmd-name]
        ["empty" "configure"] (<! (apply configure-storage storage args))
        ["configured" "activate"] (<! (apply try-activate state storage args))
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

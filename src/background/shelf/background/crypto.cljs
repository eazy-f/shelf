(ns shelf.background.crypto
  (:require [cljs.core.async :refer [<! >! close! go promise-chan]]))

(defn text-to-buffer [text]
  (.encode (js/TextEncoder.) text))

(defn buffer-to-text [buffer]
  (.decode (js/TextDecoder.) buffer))

(defn hex-to-buffer [string]
  (let [buffer-length (quot (count string) 2)
        pairs (partition 2 string)
        array (js/Uint8Array. buffer-length)]
    (dorun
     (map-indexed
      (fn [i [h l]]
        (aset array i (js/parseInt (str h l) 16)))
      pairs))
    array))

(defn buffer-to-hex [buffer]
  (apply
   str
   (for [oct (array-seq buffer)] (.padStart (.toString oct 16) 2 "0"))))

(defn key-to-hex [key]
  (let [decoded (promise-chan)]
  (go
    (.then (.exportKey js/crypto.subtle "raw" key) #(go (>! decoded %1)))
    (buffer-to-hex (js/Uint8Array. (<! decoded))))))

(defn random-byte-array [bytes]
  (.getRandomValues js/crypto (js/Uint8Array. bytes)))

(defn- cbc-cipher [iv]
  #js{:name "AES-CBC" :iv iv})

(defn- buffer-to-key [buffer]
  (let [result (promise-chan)]
    (go
      (.then
       (.importKey
        js/crypto.subtle
        "raw"
        buffer
        "AES-CBC"
        true
        #js["encrypt" "decrypt"])
       #(go (>! result %1))
       #(close! result)))
    result))

(defn encrypt [key-raw plaintext]
  "encrypt plaintext byte aara with key byte array,
   return initialization vector and ciphertext byte arrays"
  (let [iv (random-byte-array 16)
        result (promise-chan)]
    (go
      (let [key (<! (buffer-to-key key-raw))
            key-hex (<! (key-to-hex key))]
        (.then
         (.encrypt
          js/crypto.subtle
          (cbc-cipher iv)
          key
          plaintext)
        #(go (>! result [iv %1]))
        #(close! result))))
    result))

(defn decrypt [key-raw iv ciphertext]
  (let [result (promise-chan)]
    (go
      (let [key (<! (buffer-to-key key-raw))
            key-hex (<! (key-to-hex key))]
        (.then
         (.decrypt
          js/crypto.subtle
          (cbc-cipher iv)
          key
          ciphertext)
        #(go (>! result %1))
        #(close! result))))
    result))

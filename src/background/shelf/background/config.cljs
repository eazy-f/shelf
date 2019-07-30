(ns shelf.background.config
  (:require-macros [cljs.core.async :refer [go-loop go]])
  (:require [cljs.core.async :refer [chan >! <! mult]]
            [clojure.string]
            [chromex.protocols.chrome-storage-area :as browser-storage]))

(def EMPTYSTATE {:type "empty"})
(def DEFAULTCONFIG
  {:username "user@user.com"
   :password "pass"})

(defn- decrypt [stored]
  (.parse js/JSON (clojure.string/reverse stored)))

(defn- load-stored-state [storage]
  (go
    (if-some [stored (.-config (first (first (<! (browser-storage/get storage)))))]
      (into (js->clj (decrypt stored)) {:type "configured"})
      EMPTYSTATE)))

(defn- clear-storage [storage]
  (go (<! (browser-storage/remove storage "config"))))

(defn- generate-pin []
  "1112")

(defn- generate-encryption-key []
  "Donhirch0Ow2")

(defn- encrypt [config pin]
  (clojure.string/reverse (.stringify js/JSON (clj->js config))))

(defn- configure-storage [storage]
  (go
    (let [stg-encryption-key (generate-encryption-key)
          pin (generate-pin)
          stored-config (into DEFAULTCONFIG
                              {:type "active"
                               :stg-key stg-encryption-key
                               :pin pin})
          encrypted-config (encrypt stored-config pin)]
      ;FIXME: double check this read
      (when (<! (browser-storage/set storage (clj->js {:config encrypted-config})))
        (into stored-config {:pin pin})))))

(defn- try-activate [state pin]
  (if (= pin (state "pin"))
    {:type "active" :pin pin}
    state))

(defn- logout [state]
  {:type "configured" :pin (:pin state)})

(defn- handle-command [cmd state storage]
  (go
    (let [[cmd-name & args] cmd]
      (case [(:type state) cmd-name]
        ["empty" "configure"] (<! (configure-storage storage))
        ["configured" "activate"] (apply try-activate state args)
        ["configured" "clear"] (do (<! (clear-storage storage)) (<! (load-stored-state storage)))
        ["active" "clear"] (do (<! (clear-storage storage)) (<! (load-stored-state storage)))
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

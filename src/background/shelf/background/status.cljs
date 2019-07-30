(ns shelf.background.status
  (:require [cljs.core.async :refer [<! >! chan go-loop]]
            [chromex.ext.runtime :as runtime]
            [chromex.protocols.chrome-port :refer [get-name post-message!]]))

(def EMPTYSTATE {:type "empty"})

(defn- get-current-state []
  {:type "empty"})

(defn- mockup-active []
  {:type "active" :pin "1111"})

(defn- try-activate [state pin]
  (if (= pin (:pin state))
    {:type "active" :pin pin}
    state))

(defn- logout [state]
  {:type "configured" :pin (:pin state)})

(defn- handle-command [cmd state]
  (let [[cmd-name & args] cmd]
    (case [(:type state) cmd-name]
      ["empty" "configure"] (mockup-active)
      ["configured" "activate"] (apply try-activate state args)
      ["configured" "clear"] EMPTYSTATE
      ["active" "clear"] EMPTYSTATE
      ["active" "logout"] (logout state)
      state)))

(defn- serve-client [port]
  (post-message! port (clj->js (get-current-state)))
  (go-loop [state EMPTYSTATE]
    (when-some [cmd (<! port)]
      (let [new-state (handle-command cmd state)]
        (post-message! port (clj->js new-state))
        (recur new-state)))))

(defn listen []
  (let [connections (chan)]
    (runtime/tap-on-connect-events connections)
    (go-loop []
      (when-some [[event-id params] (<! connections)]
        (let [port (first params)]
          (when (= "status" (get-name port))
            (serve-client port)))
        (recur)))))

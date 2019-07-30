(ns shelf.background.config
  (:require-macros [cljs.core.async :refer [go-loop]])
  (:require [cljs.core.async :refer [chan >! <! mult]]))

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

(defn- listen [channels]
  (go-loop [state EMPTYSTATE]
    (when-some [cmd (<! (:commands channels))]
      (let [new-state (handle-command cmd state)]
        (>! (:state channels) new-state)
        (recur new-state)))))

(defn load []
  (let [channels {:state (chan) :commands (chan)}]
    (listen channels)
    {:state (mult (:state channels)) :commands (:commands channels)}))

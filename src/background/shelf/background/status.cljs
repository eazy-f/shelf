(ns shelf.background.status
  (:require [cljs.core.async :refer [<! >! chan go-loop go tap untap close!]]
            [chromex.ext.runtime :as runtime]
            [chromex.protocols.chrome-port :refer [get-name post-message! on-disconnect!]]))

(defn- serve-client [port state-chan command-chan]
  (go-loop []
    (when-some [state (<! state-chan)]
      (post-message! port (clj->js state))
      (recur)))
  (go-loop []
    (when-some [cmd (<! port)]
      (>! command-chan cmd)
      (recur)))
  (go (>! command-chan ["nop"])))

(defn listen [config-channels]
  (let [{state-mult :state
         command-chan :commands} config-channels
        connections (chan)]
    (runtime/tap-on-connect-events connections)
    (go-loop []
      (when-some [[event-id params] (<! connections)]
        (let [port (first params)]
          (when (= "status" (get-name port))
            (let [state-chan (chan)]
              (tap state-mult state-chan)
              (on-disconnect! port (fn []
                                     (close! state-chan)))
              (serve-client port state-chan command-chan))))
        (recur)))))

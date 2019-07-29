(ns shelf.background.status
  (:require [cljs.core.async :refer [<! >! chan go-loop]]
            [chromex.ext.runtime :as runtime]
            [chromex.protocols.chrome-port :refer [get-name post-message!]]))

(defn- serve-client [port]
  (go-loop []
    (when-some [msg (<! port)]
      (println msg)
      (post-message! port (clj->js {:type :empty}))
      (recur))))

(defn listen []
  (let [connections (chan)]
    (runtime/tap-on-connect-events connections)
    (go-loop []
      (when-some [[event-id params] (<! connections)]
        (let [port (first params)]
          (when (= "status" (get-name port))
            (serve-client port)))
        (recur)))))

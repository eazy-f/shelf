(ns shelf.popup
  (:require-macros [chromex.support :refer [runonce]])
  (:require [cljs.core.async :refer [<! >! go-loop]]
            [chromex.ext.runtime :as runtime]
            [chromex.protocols.chrome-port :refer [post-message!]]))

(defn- port-send [port msg]
  (post-message! port (clj->js msg)))

(defn- get-popup-area []
  (js/document.getElementById "popup-area"))

(defn- set-content [element]
  (let [parent (get-popup-area)]
    (.replaceChild parent element (get-doc-element "settings-buttons-set"))))

(defn- get-first-by-tag [parent tag]
  (.item (.getElementsByTagName parent tag) 0))

(defn- activate-buttons [buttons-set]
  (let [fieldset (get-first-by-tag buttons-set "fieldset")]
    (.removeAttribute fieldset "disabled")
    buttons-set))

(defn- get-doc-element [id]
  (js/document.getElementById id))

(defn- update-buttons [port buttons]
  (let [root-area (get-popup-area)
        starting-elements (get-doc-element "settings-buttons-set")
        active-buttons (activate-buttons (.cloneNode starting-elements true))]
    (go-loop [old-state-type nil]
      (when-some [state (<! port)]
        (let [state-type (.-type state)]
          (when (not= old-state-type state-type)
            (when-let [popup-elements
                       (case state-type
                         "empty" active-buttons
                         "configured" starting-elements
                         starting-elements)]
              (set-content popup-elements))
        (recur state-type)))))))

(defn update-status []
  (let [cinfo (clj->js {:name "status"})
        port (runtime/connect :omit cinfo)
        buttons (get-doc-element "buttons")]
    (port-send port {:type :test})
    (update-buttons port buttons)))

(runonce (update-status))

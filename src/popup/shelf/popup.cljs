(ns shelf.popup
  (:require-macros [chromex.support :refer [runonce]])
  (:require [cljs.core.async :refer [<! >! go-loop]]
            [goog.dom :as gdom]
            [chromex.ext.runtime :as runtime]
            [chromex.protocols.chrome-port :refer [post-message!]]))

(defn- port-send [port msg]
  (post-message! port (clj->js msg)))

(defn- get-popup-area []
  (js/document.getElementById "popup-area"))

(defn- get-doc-element [id]
  (js/document.getElementById id))

(defn- get-first-by-tag [parent tag]
  (.item (.getElementsByTagName parent tag) 0))

(defn- set-content [element]
  (let [parent (get-popup-area)]
    (loop []
      (when-some [child (.-firstChild parent)]
        (.removeChild parent child)
        (recur)))
    (.appendChild parent element)))

(defn- activate-buttons [port]
  (let [make-button #(gdom/createDom "button" #js{:type "button"} %1)
        configure (make-button "Configure")
        clear (make-button "Clear")
        form (gdom/createDom "form" nil configure)]
    (.addEventListener
     configure
     "click"
     (fn [event] (port-send port ["configure"])))
    (.addEventListener
     clear
     "click"
     (fn [event] (port-send port ["clear"])))
    form))

(defn- get-form-values [event]
  (into
   {}
   (for
       [e (array-seq (.-elements (.-target event)))
        :while (= "text" (.-type e))]
     [(keyword (.-name e)) (.-value e)])))

(defn- build-pin-code-form [port]
  (let [button (gdom/createDom "button" #js{:type "submit"} "Enter")
        input (gdom/createDom "input" #js{:name "pin" :type "text" :placeholder "PIN"})
        form (gdom/createDom "form" nil input button)]
    (.addEventListener form
                       "submit"
                       (fn [event]
                         (let [{:keys [pin]} (get-form-values event)]
                           (port-send port ["activate" pin])
                           (.preventDefault event))))
    form))

(defn- show-active [state port]
  (let [logout (gdom/createDom "button" #js{:type "button"} "Logout")
        text (gdom/createDom "h6" nil (.-pin state))]
    (.addEventListener
     logout
     "click"
     (fn [event] (port-send port ["logout"])))
    (gdom/createDom "form" nil text logout)))

;; < empty
;; > configure
;; < active+auto-pin
;; > show-pin+logout
;; > logout
;; < configured
;; > pin
;; < active+pin

(defn- update-buttons [port buttons]
  (let [root-area (get-popup-area)
        starting-elements (get-doc-element "startup-placeholder")
        active-buttons (activate-buttons port)
        pin-code-form (build-pin-code-form port)]
    (go-loop [old-state-type nil]
      (when-some [state (<! port)]
        (let [state-type (.-type state)]
          (when (not= old-state-type state-type)
            (set-content (case state-type
                           "empty" active-buttons
                           "configured" pin-code-form
                           "active" (show-active state port)
                           starting-elements)))
        (recur state-type))))))

(defn update-status []
  (let [cinfo (clj->js {:name "status"})
        port (runtime/connect :omit cinfo)
        buttons (get-doc-element "buttons")]
    (update-buttons port buttons)))

(runonce (update-status))

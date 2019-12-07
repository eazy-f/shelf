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

(defn- get-form-values [event]
  (into
   {}
   (for
       [e (array-seq (.-elements (.-target event)))
        :while (or (= "text" (.-type e)) (= "password" (.-type e)))]
     [(keyword (.-name e)) (.-value e)])))

(defn- set-content [element]
  (let [parent (get-popup-area)]
    (loop []
      (when-some [child (.-firstChild parent)]
        (.removeChild parent child)
        (recur)))
    (.appendChild parent element)))

(defn- make-button
  ([name] (make-button name "button"))
  ([name type] (gdom/createDom "button" #js{:type type} name)))

(defn- make-input [type name placeholder]
  (gdom/createDom "input" #js{:type type
                              :name name
                              :placeholder placeholder}))

(defn- activate-buttons [port]
  (let [passphrase (make-input "password" "pin" "Passphrase")
        api-key    (make-input "text" "apikey" "API KEY")
        bucket     (make-input "text" "bucket" "Storage bucket name")
        username   (make-input "text" "username" "User name")
        password   (make-input "password" "password" "User password")
        configure  (make-button "Configure" "submit")
        form (gdom/createDom "form" #js{:class "config"}
                             passphrase
                             (gdom/createDom "h3" nil "Firebase settings:")
                             api-key
                             bucket
                             username
                             password
                             configure)]
    (.addEventListener
     form
     "submit"
     (fn [event]
       (port-send port ["configure" (get-form-values event)])
       (.preventDefault event)))
    form))

(defn- build-pin-code-form [port]
  (let [submit (make-button "Enter" "submit")
        input (gdom/createDom "input" #js{:name "pin" :type "password" :placeholder "PIN" :style "width:60pt;"})
        clear (make-button "Clear")
        form (gdom/createDom "form" #js{:style "box-sizing:border-box;"} input submit clear)]
    (.addEventListener
     clear
     "click"
     (fn [event] (port-send port ["clear"])))
    (.addEventListener
     form
     "submit"
     (fn [event]
       (let [{:keys [pin]} (get-form-values event)]
         (port-send port ["activate" pin])
         (.preventDefault event))))
    form))

(defn- get-peer-list [port]
  ;FIXME: uncomment
  ;(list {:name "me" :status "ok"})
  '())

(defn- build-peer-info [peer]
  (let [peer-name (:name peer)
        peer-status (:status peer)
        name (gdom/createDom
                "div" #js{:class "peer-detail"}
                (gdom/createDom "text" nil peer-name))
        status (gdom/createDom
                 "div" #js{:class "peer-detail"}
                 (gdom/createDom "text" nil peer-status))]
    (gdom/createDom "div" #js{:class "peer-card"} name status)))

(defn- build-peer-list [peers]
  (map build-peer-info peers))

(defn- logout-button [port]
  (let [button (gdom/createDom "button" #js{:type "button"} "Logout")]
    (.addEventListener
     button
     "click"
     (fn [event] (port-send port ["logout"])))
    button))

(defn- show-active [port]
  (let [peers (get-peer-list port)
        logout (logout-button port)]
    (gdom/createDom "div" nil
      (apply gdom/createDom "div" nil
        (build-peer-list peers))
      (gdom/createDom "form" nil logout))))

(defn- show-welcome [state port]
  (let [logout (logout-button port)
        text (gdom/createDom "h6" nil (.-pin state))]
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
                           "active" (show-active port)
                           "first-run" (show-welcome state port)
                           starting-elements)))
        (recur state-type))))))

(defn update-status []
  (let [cinfo (clj->js {:name "status"})
        port (runtime/connect :omit cinfo)
        buttons (get-doc-element "buttons")]
    (update-buttons port buttons)))

(runonce (update-status))

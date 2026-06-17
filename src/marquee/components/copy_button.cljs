(ns marquee.components.copy-button
  "Button that copies a string to the clipboard, showing transient 'Copied!'
   feedback after a successful copy."
  (:require [reagent.core :as r]
            [marquee.components.button :refer [button]]))

(defn copy-button
  "props:
     :text     — string to copy (required)
     :label    — button label when idle (default \"Copy\")
     :size     — button size (default :sm)
     :variant  — button variant (default :outline)
     :class    — extra classes"
  [_props]
  (let [copied? (r/atom false)]
    (fn [{:keys [text label size variant class]}]
      [button {:size     (or size :sm)
               :variant  (or variant :outline)
               :class    class
               :disabled (empty? text)
               :on-click (fn []
                           (-> (.writeText js/navigator.clipboard text)
                               (.then  (fn []
                                         (reset! copied? true)
                                         (js/setTimeout #(reset! copied? false) 1500)))
                               (.catch (fn [err]
                                         (js/console.warn "Copy to clipboard failed:" err)))))}
       (if @copied? "Copied!" (or label "Copy"))])))

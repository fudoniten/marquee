(ns marquee.core)

(defn render! []
  (set! (.. js/document -body -innerHTML) "<h1>Hello, World!</h1>"))

;; Run on page load.
(render!)

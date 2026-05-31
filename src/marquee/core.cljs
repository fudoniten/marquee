(ns marquee.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [marquee.api :as api]
            [marquee.events :as events]
            [marquee.views :as views]))

(defonce root (atom nil))

(defn mount! []
  (let [el (.getElementById js/document "app")]
    (when (nil? @root)
      (reset! root (rdc/create-root el)))
    (rdc/render @root [views/app])))

(defn ^:export init []
  (api/bootstrap!)
  (rf/dispatch-sync [::events/initialize-db])
  (mount!))

;; Called by shadow-cljs after each hot reload.
(defn ^:dev/after-load reload! []
  (rf/clear-subscription-cache!)
  (mount!))

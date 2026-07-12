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

;; Backstop so an unreachable/soft-disabled service (whose martian spec never
;; loads) can't leave the app stuck on the loading screen. Healthy specs load
;; in well under this, flipping `::subs/api-ready?` via the fast path first.
(def ^:private api-ready-timeout-ms 8000)

(defn ^:export init []
  (api/bootstrap!)
  (rf/dispatch-sync [::events/initialize-db])
  (js/setTimeout #(rf/dispatch [::events/force-api-ready]) api-ready-timeout-ms)
  (rf/dispatch [::events/load-collections])
  (rf/dispatch [::events/load-app-config])
  (rf/dispatch-sync [::events/restore-from-url (.. js/window -location -pathname)])
  (.addEventListener js/window "popstate"
    (fn [_] (rf/dispatch [::events/restore-from-url (.. js/window -location -pathname)])))
  (mount!))

;; Called by shadow-cljs after each hot reload.
(defn ^:dev/after-load reload! []
  (rf/clear-subscription-cache!)
  (mount!))

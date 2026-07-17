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

;; The current in-app URL, path + query — the query carries list state (selected
;; library, page, filter; grout collection/kind/page) that restore-from-url reads.
(defn- current-url []
  (str (.. js/window -location -pathname)
       (.. js/window -location -search)))

;; Stamp the entry the app first loaded on as depth 0, so the in-page "← Back"
;; can distinguish it from entries we pushed while navigating (see :push-history).
(defn- stamp-history-root! []
  (.replaceState js/history #js{:marquee-idx 0} "" (current-url)))

(defn ^:export init []
  (api/bootstrap!)
  (rf/dispatch-sync [::events/initialize-db])
  (js/setTimeout #(rf/dispatch [::events/force-api-ready]) api-ready-timeout-ms)
  (rf/dispatch [::events/load-collections])
  (rf/dispatch [::events/load-app-config])
  (stamp-history-root!)
  (rf/dispatch-sync [::events/restore-from-url (current-url)])
  (.addEventListener js/window "popstate"
    (fn [_] (rf/dispatch [::events/restore-from-url (current-url)])))
  (mount!))

;; Called by shadow-cljs after each hot reload.
(defn ^:dev/after-load reload! []
  (rf/clear-subscription-cache!)
  (mount!))

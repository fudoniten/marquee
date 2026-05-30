(ns marquee.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   {:active-page :home
    :counter     0}))

(rf/reg-event-db
 ::navigate
 (fn [db [_ page]]
   (assoc db :active-page page)))

(rf/reg-event-db
 ::inc-counter
 (fn [db _]
   (update db :counter inc)))

(ns marquee.subs
  (:require [re-frame.core :as rf]
            [martian.re-frame :as martian]))

(rf/reg-sub
 ::active-page
 (fn [db _]
   (:active-page db)))

(rf/reg-sub
 ::counter
 (fn [db _]
   (:counter db)))

;; True once every configured martian instance has finished loading its spec.
;; Also true when no services are configured yet (so the UI is usable during dev).
;; martian.re-frame stores instances at [:martian.re-frame/martian <instance-id> :m].
(rf/reg-sub
 ::api-ready?
 (fn [db _]
   (let [instances (vals (get db :martian.re-frame/martian {}))]
     (or (empty? instances)
         (every? (comp boolean :m) instances)))))

;; Media subscriptions

(rf/reg-sub
 ::media-libraries
 (fn [db _]
   (:media-libraries db)))

(rf/reg-sub
 ::library-items
 (fn [db [_ library-id]]
   (get-in db [:library-items library-id])))

(rf/reg-sub
 ::current-media-id
 (fn [db _]
   (:current-media-id db)))

(rf/reg-sub
 ::media-item
 (fn [db [_ media-id]]
   (get-in db [:media-items media-id])))

(rf/reg-sub
 ::scheduler-metadata
 (fn [db [_ media-id]]
   (get-in db [:scheduler-metadata media-id])))

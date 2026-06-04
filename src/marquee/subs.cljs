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

;; Library selection and pagination subscriptions

(rf/reg-sub
 ::selected-library-id
 (fn [db _]
   (:selected-library-id db)))

(rf/reg-sub
 ::selected-library
 :<- [::media-libraries]
 :<- [::selected-library-id]
 (fn [[libraries selected-id] _]
   (when (and libraries selected-id)
     (first (filter #(= (:id %) selected-id) libraries)))))

(rf/reg-sub
 ::media-current-page
 (fn [db _]
   (:media-current-page db 1)))

(rf/reg-sub
 ::media-page-size
 (fn [db _]
   (:media-page-size db 20)))

(rf/reg-sub
 ::paginated-media-items
 (fn [db _]
   (let [library-id (:selected-library-id db)
         items (get-in db [:library-items library-id])
         page (:media-current-page db 1)
         page-size (:media-page-size db 20)
         start (* (dec page) page-size)
         end (+ start page-size)]
     (when items
       (subvec (vec items) start (min end (count items)))))))

(rf/reg-sub
 ::media-total-pages
 (fn [db _]
   (let [library-id (:selected-library-id db)
         page-size (:media-page-size db 20)
         items (get-in db [:library-items library-id])]
     (if items
       (js/Math.ceil (/ (count items) page-size))
       0))))

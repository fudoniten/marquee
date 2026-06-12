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

(rf/reg-sub
 ::jellyfin-url
 (fn [db _]
   (:jellyfin-url db)))

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

;; ---------------------------------------------------------------------------
;; Browse by metadata subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::browse-facet
 (fn [db _]
   (:browse-facet db :tags)))

(rf/reg-sub
 ::browse-selection
 (fn [db _]
   (:browse-selection db)))

(rf/reg-sub
 ::browse-list
 (fn [db [_ facet]]
   (get-in db [:browse-lists facet])))

(rf/reg-sub
 ::browse-filter
 (fn [db _]
   (:browse-filter db "")))

(rf/reg-sub
 ::browse-media
 (fn [db [_ facet value]]
   (get-in db [:browse-media [facet value]])))

(rf/reg-sub
 ::browse-media-page
 (fn [db _]
   (:browse-media-page db 1)))

;; API documentation browser subscriptions

(rf/reg-sub
 ::api-selected-service
 (fn [db _]
   (:api-selected-service db)))

(rf/reg-sub
 ::api-spec
 (fn [db [_ service-id]]
   (get-in db [:api-specs service-id])))

(rf/reg-sub
 ::api-expanded-ops
 (fn [db _]
   (:api-expanded-ops db #{})))

(rf/reg-sub
 ::api-filter
 (fn [db _]
   (:api-filter db "")))

;; ---------------------------------------------------------------------------
;; Schedule / guide subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::channels
 (fn [db _] (:channels db)))

(rf/reg-sub
 ::channels-loading?
 (fn [db _] (:channels-loading? db false)))

(rf/reg-sub
 ::schedule-window-start
 (fn [db _] (:schedule-window-start db (.getTime (js/Date.)))))

(rf/reg-sub
 ::all-channel-events
 (fn [db _] (:channel-events db {})))

(rf/reg-sub
 ::channel-events-loading?
 (fn [db _] (boolean (seq (:channel-events-loading db)))))

(rf/reg-sub
 ::current-channel-id
 (fn [db _] (:current-channel-id db)))

(rf/reg-sub
 ::current-channel
 :<- [::channels]
 :<- [::current-channel-id]
 (fn [[channels id] _]
   (when (and channels id)
     (first (filter #(= (:id %) id) channels)))))

(rf/reg-sub
 ::current-channel-events
 (fn [db _]
   (let [id (:current-channel-id db)]
     (get-in db [:channel-events id]))))

(rf/reg-sub
 ::channel-events-loading-for?
 (fn [db [_ channel-id]]
   (contains? (:channel-events-loading db #{}) channel-id)))

;; ---------------------------------------------------------------------------
;; Jobs subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::jobs
 (fn [db _] (:jobs db)))

(rf/reg-sub
 ::jobs-loading?
 (fn [db _] (:jobs-loading? db false)))

(rf/reg-sub
 ::action-state
 (fn [db [_ action-key]]
   (get-in db [:action-states action-key] {:status :idle})))

(rf/reg-sub
 ::tag-task-options
 (fn [db _]
   (:tag-task-options db {:dry-run true :target-limit nil})))

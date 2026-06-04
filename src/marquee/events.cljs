(ns marquee.events
  (:require [re-frame.core :as rf]
            [martian.re-frame :as martian]))

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   {:active-page :home
    :counter     0
    :media-libraries nil
    :library-items {}
    :media-items {}
    :scheduler-metadata {}
    :current-media-id nil}))

(rf/reg-event-db
 ::navigate
 (fn [db [_ page]]
   (let [db' (assoc db :active-page page)]
     ;; Load data when navigating to media page
     (when (= page :media)
       (rf/dispatch [::load-media-libraries]))
     db')))

(rf/reg-event-db
 ::navigate-to-media-detail
 (fn [db [_ media-id]]
   (let [db' (-> db
                 (assoc :active-page :media-detail)
                 (assoc :current-media-id media-id))]
     ;; Load media item and metadata
     (rf/dispatch [::load-media-item media-id])
     (rf/dispatch [::load-scheduler-metadata media-id])
     db')))

(rf/reg-event-db
 ::inc-counter
 (fn [db _]
   (update db :counter inc)))

;; Media events

(rf/reg-event-fx
 ::load-media-libraries
 (fn [{:keys [db]} _]
   {:db db
    :dispatch [::martian/request
               :pseudovision
               :get-api-media-libraries
               {}
               [::load-media-libraries-success]
               [::load-media-libraries-failure]]}))

(rf/reg-event-fx
 ::load-media-libraries-success
 (fn [{:keys [db]} [_ response]]
   (let [libraries (:body response)]
     {:db (assoc db :media-libraries libraries)
      :dispatch-n (for [lib libraries]
                    [::load-library-items (:id lib)])})))

(rf/reg-event-db
 ::load-media-libraries-failure
 (fn [db [_ response]]
   (js/console.error "Failed to load libraries:" response)
   (assoc db :media-libraries [])))

(rf/reg-event-fx
 ::load-library-items
 (fn [{:keys [db]} [_ library-id]]
   {:db db
    :dispatch [::martian/request
               :pseudovision
               :get-api-media-libraries-id-items
               {:id library-id}
               [::load-library-items-success library-id]
               [::load-library-items-failure library-id]]}))

(rf/reg-event-db
 ::load-library-items-success
 (fn [db [_ library-id response]]
   (let [items (:body response)]
     (assoc-in db [:library-items library-id] items))))

(rf/reg-event-db
 ::load-library-items-failure
 (fn [db [_ library-id response]]
   (js/console.error "Failed to load library items:" library-id response)
   (assoc-in db [:library-items library-id] [])))

(rf/reg-event-fx
 ::load-media-item
 (fn [{:keys [db]} [_ media-id]]
   {:db db
    :dispatch [::martian/request
               :pseudovision
               :get-api-media-items-id
               {:id media-id}
               [::load-media-item-success media-id]
               [::load-media-item-failure media-id]]}))

(rf/reg-event-db
 ::load-media-item-success
 (fn [db [_ media-id response]]
   (assoc-in db [:media-items media-id] (:body response))))

(rf/reg-event-db
 ::load-media-item-failure
 (fn [db [_ media-id response]]
   (js/console.error "Failed to load media item:" media-id response)
   (assoc-in db [:media-items media-id] false)))

(rf/reg-event-fx
 ::load-scheduler-metadata
 (fn [{:keys [db]} [_ media-id]]
   {:db db
    :dispatch [::martian/request
               :tunarr-scheduler
               :get-api-media-item-media-id
               {:media-id media-id}
               [::load-scheduler-metadata-success media-id]
               [::load-scheduler-metadata-failure media-id]]}))

(rf/reg-event-db
 ::load-scheduler-metadata-success
 (fn [db [_ media-id response]]
   (assoc-in db [:scheduler-metadata media-id] (:body response))))

(rf/reg-event-db
 ::load-scheduler-metadata-failure
 (fn [db [_ media-id response]]
   (js/console.error "Failed to load scheduler metadata:" media-id response)
   (assoc-in db [:scheduler-metadata media-id] false)))

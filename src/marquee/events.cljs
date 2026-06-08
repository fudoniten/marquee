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
    :current-media-id nil
    :selected-library-id nil
    :media-current-page 1
    :media-page-size 20
    :api-specs {}
    :api-selected-service nil
    :api-expanded-ops #{}
    :api-filter ""}))

(rf/reg-event-db
 ::navigate
 (fn [db [_ page]]
   (let [db' (assoc db :active-page page)]
     ;; Load data when navigating to media page
     (when (= page :media)
       (rf/dispatch [::load-media-libraries])
       ;; Reset to first page when navigating to media
       (rf/dispatch [::set-media-page 1]))
     ;; Default to the first service when first visiting the API docs.
     (when (and (= page :api-docs) (nil? (:api-selected-service db)))
       (rf/dispatch [::select-api-service :pseudovision]))
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
               :get-api-media-libraries
               {::martian/instance-id :pseudovision}
               [::load-media-libraries-success]
               [::load-media-libraries-failure]]}))

(rf/reg-event-fx
 ::load-media-libraries-success
 (fn [{:keys [db]} [_ response]]
   (let [body (:body response)
         ;; Extract items from paginated response
         libraries (if (map? body)
                     (:items body)  ; New paginated format
                     body)          ; Fallback for non-paginated (backward compat)
         first-library (first libraries)]
     {:db (assoc db :media-libraries libraries)
      ;; Auto-select first library if none selected
      :dispatch (when (and first-library (nil? (:selected-library-id db)))
                  [::select-library (:id first-library)])})))

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
               :get-api-media-libraries-id-items
               {::martian/instance-id :pseudovision
                :id library-id}
               [::load-library-items-success library-id]
               [::load-library-items-failure library-id]]}))

(rf/reg-event-db
 ::load-library-items-success
 (fn [db [_ library-id response]]
   (let [body (:body response)
         ;; Extract items from paginated response
         items (if (map? body)
                 (:items body)  ; New paginated format
                 body)]         ; Fallback for non-paginated (backward compat)
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
               :get-api-media-items-id
               {::martian/instance-id :pseudovision
                :id media-id}
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
               :get-api-media-item-media-id
               {::martian/instance-id :tunarr-scheduler
                :media-id media-id}
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

;; Library selection and pagination events

(rf/reg-event-fx
 ::select-library
 (fn [{:keys [db]} [_ library-id]]
   (let [already-loaded? (get-in db [:library-items library-id])]
     {:db (-> db
              (assoc :selected-library-id library-id)
              (assoc :media-current-page 1))
      :dispatch (when-not already-loaded?
                  [::load-library-items library-id])})))

(rf/reg-event-db
 ::set-media-page
 (fn [db [_ page]]
   (assoc db :media-current-page page)))

(rf/reg-event-db
 ::set-media-page-size
 (fn [db [_ size]]
   (-> db
       (assoc :media-page-size size)
       (assoc :media-current-page 1))))

;; API documentation browser events

;; Plain fetch of an OpenAPI spec from the BFF as a JSON map (string keys).
(rf/reg-fx
 ::fetch-json
 (fn [{:keys [url on-success on-failure]}]
   (-> (js/fetch url)
       (.then (fn [resp]
                (if (.-ok resp)
                  (.json resp)
                  (throw (js/Error. (str "HTTP " (.-status resp) " " (.-statusText resp)))))))
       (.then (fn [data] (rf/dispatch (conj on-success (js->clj data)))))
       (.catch (fn [err] (rf/dispatch (conj on-failure (.-message err))))))))

(rf/reg-event-fx
 ::load-api-spec
 (fn [{:keys [db]} [_ service-id]]
   ;; Only fetch once per service.
   (if (get-in db [:api-specs service-id])
     {:db db}
     {:db (assoc-in db [:api-specs service-id] {:status :loading})
      ::fetch-json {:url        (str "/api/" (name service-id) "/openapi.json")
                    :on-success [::load-api-spec-success service-id]
                    :on-failure [::load-api-spec-failure service-id]}})))

(rf/reg-event-db
 ::load-api-spec-success
 (fn [db [_ service-id spec]]
   (assoc-in db [:api-specs service-id] {:status :loaded :spec spec})))

(rf/reg-event-db
 ::load-api-spec-failure
 (fn [db [_ service-id error]]
   (js/console.error "Failed to load API spec:" (name service-id) error)
   (assoc-in db [:api-specs service-id] {:status :error :error error})))

(rf/reg-event-fx
 ::select-api-service
 (fn [{:keys [db]} [_ service-id]]
   {:db       (assoc db :api-selected-service service-id :api-filter "")
    :dispatch [::load-api-spec service-id]}))

(rf/reg-event-db
 ::toggle-api-operation
 (fn [db [_ op-key]]
   (update db :api-expanded-ops
           (fn [expanded]
             (let [expanded (or expanded #{})]
               (if (contains? expanded op-key)
                 (disj expanded op-key)
                 (conj expanded op-key)))))))

(rf/reg-event-db
 ::set-api-filter
 (fn [db [_ text]]
   (assoc db :api-filter text)))

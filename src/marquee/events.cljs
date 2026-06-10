(ns marquee.events
  (:require [re-frame.core :as rf]
            [martian.re-frame :as martian]
            [marquee.routes :as routes]))

(rf/reg-fx
 :push-history
 (fn [path]
   (.pushState js/history nil "" path)))

(def ^:private grid-window-ms (* 2 60 60 1000))

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
    :jellyfin-url nil
    :api-specs {}
    :api-selected-service nil
    :api-expanded-ops #{}
    :api-filter ""
    ;; Schedule / guide state
    :channels nil
    :channels-loading? false
    :channel-events {}           ; channel-id → [PlayoutEvent ...]
    :channel-events-loading #{}  ; set of channel-ids currently loading
    :schedule-window-start (.getTime (js/Date.))
    :current-channel-id nil}))

(rf/reg-event-fx
 ::load-app-config
 (fn [{:keys [db]} _]
   {:db db
    ::fetch-json {:url        "/api/config"
                  :on-success [::load-app-config-success]
                  :on-failure [::load-app-config-failure]}}))

(rf/reg-event-db
 ::load-app-config-success
 (fn [db [_ config]]
   (assoc db :jellyfin-url (get config "jellyfin-url"))))

(rf/reg-event-db
 ::load-app-config-failure
 (fn [db [_ err]]
   (js/console.warn "Could not load app config:" err)
   db))

(rf/reg-event-fx
 ::navigate
 (fn [{:keys [db]} [_ page]]
   (let [dispatches (concat
                      (when (= page :media)
                        [[::load-media-libraries] [::set-media-page 1]])
                      (when (and (= page :api-docs) (nil? (:api-selected-service db)))
                        [[::select-api-service :pseudovision]])
                      (when (= page :schedule-grid)
                        [[::load-channels]])]
     (cond-> {:db           (assoc db :active-page page)
              :push-history (routes/page->path page)}
       (seq dispatches) (assoc :dispatch-n dispatches)))))

(rf/reg-event-fx
 ::navigate-to-media-detail
 (fn [{:keys [db]} [_ media-id]]
   {:db           (-> db
                      (assoc :active-page :media-detail)
                      (assoc :current-media-id media-id))
    :push-history (routes/media-detail-path media-id)
    ;; Scheduler metadata is loaded from ::load-media-item-success, because
    ;; Tunarr Scheduler keys its catalog by the item's Jellyfin remote-key,
    ;; which we only know once the Pseudovision item arrives.
    :dispatch     [::load-media-item media-id]}))

(rf/reg-event-fx
 ::restore-from-url
 (fn [{:keys [db]} [_ path]]
   (let [{:keys [page media-id channel-id]} (or (routes/parse-path path) {:page :home})
         dispatches (concat
                      (when (= page :media)
                        [[::load-media-libraries] [::set-media-page 1]])
                      (when (and (= page :api-docs) (nil? (:api-selected-service db)))
                        [[::select-api-service :pseudovision]])
                      (when (= page :media-detail)
                        [[::load-media-item media-id]])
                      (when (= page :schedule-grid)
                        [[::load-channels]])
                      (when (= page :channel-schedule)
                        (cond-> (when (nil? (:channels db)) [[::load-channels]])
                          channel-id (conj [::load-channel-events channel-id]))))]
     (cond-> {:db (cond-> (assoc db :active-page page)
                    (= page :media-detail)     (assoc :current-media-id media-id)
                    (= page :channel-schedule) (assoc :current-channel-id channel-id))}
       (seq dispatches) (assoc :dispatch-n dispatches)))))

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

(rf/reg-event-fx
 ::load-media-item-success
 (fn [{:keys [db]} [_ media-id response]]
   (let [item       (:body response)
         remote-key (:remote-key item)
         db         (assoc-in db [:media-items media-id] item)]
     ;; Tunarr Scheduler syncs from Pseudovision but keys its catalog by the
     ;; Jellyfin remote-key, not Pseudovision's numeric id.
     (if remote-key
       {:db db :dispatch [::load-scheduler-metadata media-id remote-key]}
       {:db (assoc-in db [:scheduler-metadata media-id] false)}))))

(rf/reg-event-db
 ::load-media-item-failure
 (fn [db [_ media-id response]]
   (js/console.error "Failed to load media item:" media-id response)
   (assoc-in db [:media-items media-id] false)))

(rf/reg-event-fx
 ::load-scheduler-metadata
 (fn [{:keys [db]} [_ media-id remote-key]]
   ;; remote-key is the Jellyfin id Tunarr Scheduler uses as its catalog id;
   ;; results are stored under the Pseudovision media-id for the UI.
   {:db db
    :dispatch [::martian/request
               :get-api-media-item-media-id
               {::martian/instance-id :tunarr-scheduler
                :media-id (str remote-key)}
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

;; ---------------------------------------------------------------------------
;; Schedule / guide events
;;
;; Data model:
;;   Channels:  GET /api/channels        → PaginatedChannels {:items [...]}
;;   Events:    GET /api/channels/:channel-id/playout/events
;;              → PaginatedPlayoutEvents {:items [{:start-at, :finish-at,
;;                :guide-start-at, :guide-finish-at, :custom-title,
;;                :media-item-id, :kind, ...}]}
;;              Cursor = ISO-8601 timestamp of the last event's :start-at.
;;              Passing no cursor returns events from now onwards.
;;
;; Martian operationIds (BFF-generated from reitit paths):
;;   get-api-channels
;;   get-api-channels-channel-id-playout-events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::load-channels
 (fn [{:keys [db]} _]
   {:db       (assoc db :channels-loading? true)
    :dispatch [::martian/request
               :get-api-channels
               {::martian/instance-id :pseudovision}
               [::load-channels-success]
               [::load-channels-failure]]}))

(rf/reg-event-fx
 ::load-channels-success
 (fn [{:keys [db]} [_ response]]
   (let [body     (:body response)
         channels (if (map? body) (:items body) body)]
     {:db         (-> db
                      (assoc :channels channels)
                      (assoc :channels-loading? false))
      ;; Kick off event loading for each channel so the guide has data.
      :dispatch-n (mapv (fn [ch] [::load-channel-events (:id ch)]) channels)})))

(rf/reg-event-db
 ::load-channels-failure
 (fn [db [_ response]]
   (js/console.error "Failed to load channels:" response)
   (-> db (assoc :channels []) (assoc :channels-loading? false))))

;; Load playout events for a single channel (for guide grid or channel page).
;; cursor is an optional ISO-8601 string; omitting it returns events from now.
(rf/reg-event-fx
 ::load-channel-events
 (fn [{:keys [db]} [_ channel-id]]
   {:db       (update db :channel-events-loading (fnil conj #{}) channel-id)
    :dispatch [::martian/request
               :get-api-channels-channel-id-playout-events
               {::martian/instance-id :pseudovision
                :channel-id           channel-id
                :limit                50}
               [::load-channel-events-success channel-id]
               [::load-channel-events-failure channel-id]]}))

(rf/reg-event-db
 ::load-channel-events-success
 (fn [db [_ channel-id response]]
   (let [body  (:body response)
         items (if (map? body) (:items body) body)]
     (-> db
         (assoc-in [:channel-events channel-id] items)
         (update :channel-events-loading disj channel-id)))))

(rf/reg-event-db
 ::load-channel-events-failure
 (fn [db [_ channel-id response]]
   (js/console.error "Failed to load channel events:" channel-id response)
   (-> db
       (assoc-in [:channel-events channel-id] [])
       (update :channel-events-loading disj channel-id))))

(rf/reg-event-fx
 ::schedule-window-forward
 (fn [{:keys [db]} _]
   (let [channels (or (:channels db) [])]
     {:db         (update db :schedule-window-start + grid-window-ms)
      ;; Reload events so we have data for the new window.
      :dispatch-n (mapv (fn [ch] [::load-channel-events (:id ch)]) channels)})))

(rf/reg-event-fx
 ::schedule-window-back
 (fn [{:keys [db]} _]
   (let [channels (or (:channels db) [])]
     {:db         (update db :schedule-window-start - grid-window-ms)
      :dispatch-n (mapv (fn [ch] [::load-channel-events (:id ch)]) channels)})))

(rf/reg-event-fx
 ::schedule-window-reset
 (fn [{:keys [db]} _]
   (let [channels (or (:channels db) [])]
     {:db         (assoc db :schedule-window-start (.getTime (js/Date.)))
      :dispatch-n (mapv (fn [ch] [::load-channel-events (:id ch)]) channels)})))

(rf/reg-event-fx
 ::navigate-to-channel
 (fn [{:keys [db]} [_ channel-id]]
   (let [need-channels? (nil? (:channels db))
         need-events?   (nil? (get-in db [:channel-events channel-id]))]
     {:db           (-> db
                        (assoc :active-page :channel-schedule)
                        (assoc :current-channel-id channel-id))
      :push-history (routes/channel-path channel-id)
      :dispatch-n   (cond-> []
                      need-channels? (conj [::load-channels])
                      need-events?   (conj [::load-channel-events channel-id]))})))

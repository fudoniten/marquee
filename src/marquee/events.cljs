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
    :current-channel-id nil
    ;; Jobs state
    :jobs nil
    :jobs-loading? false
    ;; Action states: action-key → {:status :idle|:loading|:success|:error :message "..."}
    :action-states {}}))

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
                        [[::load-channels]])
                      (when (= page :jobs)
                        [[::load-jobs]]))]
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
     ;; Tunarr Scheduler keys its catalog by Pseudovision's numeric id.
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
   ;; Tunarr Scheduler keys its catalog by Pseudovision's numeric id.
   {:db db
    :dispatch [::martian/request
               :get-api-media-item-media-id
               {::martian/instance-id :tunarr-scheduler
                :media-id (str media-id)}
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

;; ---------------------------------------------------------------------------
;; Jobs
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::load-jobs
 (fn [{:keys [db]} _]
   {:db (assoc db :jobs-loading? true)
    :dispatch [::martian/request
               :get-api-jobs
               {::martian/instance-id :tunarr-scheduler}
               [::load-jobs-success]
               [::load-jobs-failure]]}))

(rf/reg-event-db
 ::load-jobs-success
 (fn [db [_ response]]
   (let [body (:body response)
         jobs (if (map? body) (or (:jobs body) (vals body)) body)]
     (-> db
         (assoc :jobs (vec (sort-by #(or (:created-at %) "") > (or jobs []))))
         (assoc :jobs-loading? false)))))

(rf/reg-event-db
 ::load-jobs-failure
 (fn [db [_ response]]
   (js/console.error "Failed to load jobs:" response)
   (-> db (assoc :jobs []) (assoc :jobs-loading? false))))

;; ---------------------------------------------------------------------------
;; Action state helpers
;; ---------------------------------------------------------------------------

(rf/reg-fx
 ::timeout
 (fn [{:keys [ms dispatch]}]
   (js/setTimeout #(rf/dispatch dispatch) ms)))

(rf/reg-event-db
 ::set-action-state
 (fn [db [_ action-key status message]]
   (assoc-in db [:action-states action-key] {:status status :message message})))

(rf/reg-event-fx
 ::clear-action-state
 (fn [{:keys [db]} [_ action-key]]
   {:db (update db :action-states dissoc action-key)}))

(defn- action-success-fx [action-key message]
  {:dispatch-n [[::set-action-state action-key :success message]
                [::load-jobs]]
   ::timeout   {:ms 3000 :dispatch [::clear-action-state action-key]}})

(defn- action-error-fx [action-key response]
  (let [err (or (get-in response [:body :message])
                (get-in response [:body :error])
                (str "Error " (:status response)))]
    {:dispatch   [::set-action-state action-key :error err]
     ::timeout   {:ms 5000 :dispatch [::clear-action-state action-key]}}))

;; ---------------------------------------------------------------------------
;; Pseudovision triggers
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::trigger-scan-library
 (fn [{:keys [db]} [_ library-id]]
   (let [k [:scan-library library-id]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :post-api-media-libraries-id-scan
                 {::martian/instance-id :pseudovision
                  :id library-id}
                 [::trigger-scan-library-success library-id]
                 [::trigger-scan-library-failure library-id]]})))

(rf/reg-event-fx
 ::trigger-scan-library-success
 (fn [_ [_ library-id _response]]
   (action-success-fx [:scan-library library-id] "Scan triggered")))

(rf/reg-event-fx
 ::trigger-scan-library-failure
 (fn [_ [_ library-id response]]
   (action-error-fx [:scan-library library-id] response)))

(rf/reg-event-fx
 ::trigger-rebuild-playout
 (fn [{:keys [db]} [_ channel-id]]
   (let [k [:rebuild-playout channel-id]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :post-api-channels-channel-id-playout
                 {::martian/instance-id :pseudovision
                  :channel-id channel-id}
                 [::trigger-rebuild-playout-success channel-id]
                 [::trigger-rebuild-playout-failure channel-id]]})))

(rf/reg-event-fx
 ::trigger-rebuild-playout-success
 (fn [_ [_ channel-id _response]]
   (action-success-fx [:rebuild-playout channel-id] "Playout rebuilt")))

(rf/reg-event-fx
 ::trigger-rebuild-playout-failure
 (fn [_ [_ channel-id response]]
   (action-error-fx [:rebuild-playout channel-id] response)))

;; ---------------------------------------------------------------------------
;; Tunarr-Scheduler triggers
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::trigger-sync-libraries
 (fn [{:keys [db]} _]
   {:db       (assoc-in db [:action-states :sync-libraries] {:status :loading})
    :dispatch [::martian/request
               :post-api-media-sync-libraries
               {::martian/instance-id :tunarr-scheduler}
               [::trigger-sync-libraries-success]
               [::trigger-sync-libraries-failure]]}))

(rf/reg-event-fx
 ::trigger-sync-libraries-success
 (fn [_ _]
   (action-success-fx :sync-libraries "Libraries synced")))

(rf/reg-event-fx
 ::trigger-sync-libraries-failure
 (fn [_ [_ response]]
   (action-error-fx :sync-libraries response)))

(rf/reg-event-fx
 ::trigger-sync-channels
 (fn [{:keys [db]} _]
   {:db       (assoc-in db [:action-states :sync-channels] {:status :loading})
    :dispatch [::martian/request
               :post-api-channels-sync-pseudovision
               {::martian/instance-id :tunarr-scheduler}
               [::trigger-sync-channels-success]
               [::trigger-sync-channels-failure]]}))

(rf/reg-event-fx
 ::trigger-sync-channels-success
 (fn [_ _]
   (action-success-fx :sync-channels "Channels synced")))

(rf/reg-event-fx
 ::trigger-sync-channels-failure
 (fn [_ [_ response]]
   (action-error-fx :sync-channels response)))

(defn- library-action-op [action]
  (case action
    :rescan              :post-api-media-library-rescan
    :retag               :post-api-media-library-retag
    :add-taglines        :post-api-media-library-add-taglines
    :recategorize        :post-api-media-library-recategorize
    :retag-episodes      :post-api-media-library-retag-episodes
    :sync-pseudovision-tags :post-api-media-library-sync-pseudovision-tags))

(defn- library-action-label [action]
  (case action
    :rescan              "Rescan started"
    :retag               "Retag started"
    :add-taglines        "Tagline generation started"
    :recategorize        "Recategorization started"
    :retag-episodes      "Episode retag started"
    :sync-pseudovision-tags "Tag sync started"))

(rf/reg-event-fx
 ::trigger-library-action
 (fn [{:keys [db]} [_ action library-name]]
   (let [k [action library-name]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 (library-action-op action)
                 {::martian/instance-id :tunarr-scheduler
                  :library library-name}
                 [::trigger-library-action-success action library-name]
                 [::trigger-library-action-failure action library-name]]})))

(rf/reg-event-fx
 ::trigger-library-action-success
 (fn [_ [_ action library-name _response]]
   (action-success-fx [action library-name] (library-action-label action))))

(rf/reg-event-fx
 ::trigger-library-action-failure
 (fn [_ [_ action library-name response]]
   (action-error-fx [action library-name] response)))

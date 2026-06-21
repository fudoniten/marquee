(ns marquee.events
  (:require [clojure.string]
            [re-frame.core :as rf]
            [martian.re-frame :as martian]
            [marquee.routes :as routes]))

(rf/reg-fx
 :push-history
 (fn [path]
   (.pushState js/history nil "" path)))

;; The raw cljs-http response map prints as an opaque CLJS object in the
;; browser console, so surface the status and body readably instead.
(defn- log-request-failure [message {:keys [status error-text body]}]
  (js/console.error message
                    (str "status=" status
                         (when-not (empty? error-text)
                           (str " (" error-text ")")))
                    (pr-str body)))

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
    ;; Browse-by-metadata state (Tunarr Scheduler browse endpoints)
    :browse-facet :tags          ; :tags | :genres | :channels
    :browse-selection nil        ; selected tag/genre/channel name, or nil
    :browse-lists {}             ; facet → vector of facet entries
    :browse-media {}             ; [facet value] → vector of media items
    :browse-media-page 1
    :browse-filter ""
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
    ;; Jobs state: jobs are fetched from both Tunarr Scheduler and
    ;; Pseudovision (which now runs its own jobs, e.g. playout generation),
    ;; keyed by source so the two loads don't clobber each other.
    :jobs-by-source {}
    :jobs-loading #{}
    ;; Options for the catalog-wide tag curation tasks (Jobs page panel).
    ;; Dry-run defaults to on so nothing is deleted without reviewing first.
    :tag-task-options {:dry-run true :target-limit nil}
    ;; Action states: action-key → {:status :idle|:loading|:success|:error :message "..."}
    :action-states {}
    ;; Collections (persisted to localStorage)
    :collections {}              ; id → {:id :name :items [media-id ...] :created-at ms}
    :current-collection-id nil
    :new-collection-name ""
    :add-to-collection-open? false}))

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
   (-> db
       (assoc :jellyfin-url     (get config "jellyfin-url"))
       (assoc :pseudovision-url (get config "pseudovision-url")))))

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
                      (when (= page :browse)
                        [[::load-browse-facet (or (:browse-facet db) :tags)]])
                      (when (and (= page :api-docs) (nil? (:api-selected-service db)))
                        [[::select-api-service :pseudovision]])
                      (when (= page :schedule-grid)
                        [[::load-channels]])
                      (when (= page :jobs)
                        [[::load-jobs]])
                      (when (= page :collections)
                        [[::load-collections]]))]
     (cond-> {:db           (cond-> (assoc db :active-page page)
                              (= page :browse) (assoc :browse-selection nil)
                              (= page :collections) (assoc :current-collection-id nil))
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
   (let [{:keys [page media-id channel-id collection-id facet selection]}
         (or (routes/parse-path path) {:page :home})
         dispatches (concat
                      (when (= page :media)
                        [[::load-media-libraries] [::set-media-page 1]])
                      (when (and (= page :api-docs) (nil? (:api-selected-service db)))
                        [[::select-api-service :pseudovision]])
                      (when (= page :media-detail)
                        [[::load-media-item media-id]])
                      (when (= page :browse)
                        (cond-> [[::load-browse-facet (or facet :tags)]]
                          selection (conj [::load-browse-media (or facet :tags) selection])))
                      (when (= page :schedule-grid)
                        [[::load-channels]])
                      (when (= page :channel-schedule)
                        (cond-> (when (nil? (:channels db)) [[::load-channels]])
                          channel-id (conj [::load-channel-events channel-id])))
                      (when (#{:collections :collection-detail} page)
                        [[::load-collections]]))]
     (cond-> {:db (cond-> (assoc db :active-page page)
                    (= page :media-detail)      (assoc :current-media-id media-id)
                    (= page :browse)            (assoc :browse-facet (or facet :tags)
                                                       :browse-selection selection
                                                       :browse-media-page 1)
                    (= page :channel-schedule)  (assoc :current-channel-id channel-id)
                    (= page :collection-detail) (assoc :current-collection-id collection-id))}
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
     ;; Auto-select first library if none selected
     (cond-> {:db (assoc db :media-libraries libraries)}
       (and first-library (nil? (:selected-library-id db)))
       (assoc :dispatch [::select-library (:id first-library)])))))

(rf/reg-event-db
 ::load-media-libraries-failure
 (fn [db [_ response]]
   (log-request-failure "Failed to load libraries:" response)
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
   (log-request-failure (str "Failed to load library items: " library-id) response)
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
   (log-request-failure (str "Failed to load media item: " media-id) response)
   (assoc-in db [:media-items media-id] false)))

;; Lightweight loader used to resolve a media item's name for display (e.g. the
;; schedule/guide). Unlike ::load-media-item it does not fetch scheduler
;; metadata, and it skips items that are already cached or in flight so loading
;; a channel's playout doesn't fan out into a request storm.
(rf/reg-event-fx
 ::ensure-media-item
 (fn [{:keys [db]} [_ media-id]]
   (if (or (contains? (:media-items db) media-id)
           (contains? (:media-items-loading db) media-id))
     {:db db}
     {:db       (update db :media-items-loading (fnil conj #{}) media-id)
      :dispatch [::martian/request
                 :get-api-media-items-id
                 {::martian/instance-id :pseudovision
                  :id media-id}
                 [::ensure-media-item-success media-id]
                 [::ensure-media-item-failure media-id]]})))

(rf/reg-event-db
 ::ensure-media-item-success
 (fn [db [_ media-id response]]
   (-> db
       (assoc-in [:media-items media-id] (:body response))
       (update :media-items-loading disj media-id))))

(rf/reg-event-db
 ::ensure-media-item-failure
 (fn [db [_ media-id response]]
   (log-request-failure (str "Failed to load media item: " media-id) response)
   (-> db
       (assoc-in [:media-items media-id] false)
       (update :media-items-loading disj media-id))))

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
   (log-request-failure (str "Failed to load scheduler metadata: " media-id) response)
   (assoc-in db [:scheduler-metadata media-id] false)))

;; Library selection and pagination events

(rf/reg-event-fx
 ::select-library
 (fn [{:keys [db]} [_ library-id]]
   (let [already-loaded? (get-in db [:library-items library-id])]
     (cond-> {:db (-> db
                      (assoc :selected-library-id library-id)
                      (assoc :media-current-page 1))}
       (not already-loaded?)
       (assoc :dispatch [::load-library-items library-id])))))

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

;; ---------------------------------------------------------------------------
;; Browse by metadata (Tunarr Scheduler browse endpoints)
;;
;;   GET /api/tags                                  → {:tags [{:tag :usage-count :example-titles}]}
;;   GET /api/tags/:tag/media                       → {:media [...]}
;;   GET /api/genres                                → {:genres ["..."]}
;;   GET /api/genres/:genre/media                   → {:media [...]}
;;   GET /api/catalog/channels                      → {:channels [{:name :full-name :description}]}
;;   GET /api/catalog/channels/:channel-name/media  → {:media [...]}
;;
;; Media items come back with namespaced keys (tunarr.scheduler.media/name);
;; we strip the namespaces on receipt so views can use plain :name, :tags, etc.
;; ---------------------------------------------------------------------------

(defn- key-name [k]
  (if (keyword? k) (name k) (str k)))

(defn- strip-key-namespaces [m]
  (into {} (map (fn [[k v]] [(keyword (key-name k)) v])) m))

(defn- browse-list-op [facet]
  (case facet
    :tags     :get-api-tags
    :genres   :get-api-genres
    :channels :get-api-catalog-channels))

(defn- browse-media-request [facet value]
  (case facet
    :tags     [:get-api-tags-tag-media {:tag value}]
    :genres   [:get-api-genres-genre-media {:genre value}]
    :channels [:get-api-catalog-channels-channel-name-media {:channel-name value}]))

(rf/reg-event-fx
 ::load-browse-facet
 (fn [{:keys [db]} [_ facet]]
   ;; Facet lists are cached for the session; reload only when missing.
   (if (get-in db [:browse-lists facet])
     {:db db}
     {:db db
      :dispatch [::martian/request
                 (browse-list-op facet)
                 {::martian/instance-id :tunarr-scheduler}
                 [::load-browse-facet-success facet]
                 [::load-browse-facet-failure facet]]})))

(rf/reg-event-db
 ::load-browse-facet-success
 (fn [db [_ facet response]]
   (let [body  (:body response)
         items (case facet
                 :tags     (:tags body)
                 :genres   (:genres body)
                 :channels (:channels body))]
     (assoc-in db [:browse-lists facet] (vec items)))))

(rf/reg-event-db
 ::load-browse-facet-failure
 (fn [db [_ facet response]]
   (log-request-failure (str "Failed to load browse facet: " (name facet)) response)
   (assoc-in db [:browse-lists facet] [])))

(rf/reg-event-fx
 ::load-browse-media
 (fn [{:keys [db]} [_ facet value]]
   (if (get-in db [:browse-media [facet value]])
     {:db db}
     (let [[op params] (browse-media-request facet value)]
       {:db db
        :dispatch [::martian/request
                   op
                   (assoc params ::martian/instance-id :tunarr-scheduler)
                   [::load-browse-media-success facet value]
                   [::load-browse-media-failure facet value]]}))))

(rf/reg-event-db
 ::load-browse-media-success
 (fn [db [_ facet value response]]
   (let [media (->> (get-in response [:body :media])
                    (mapv strip-key-namespaces))]
     (assoc-in db [:browse-media [facet value]] media))))

(rf/reg-event-db
 ::load-browse-media-failure
 (fn [db [_ facet value response]]
   (log-request-failure (str "Failed to load media for " (name facet) " " value) response)
   (assoc-in db [:browse-media [facet value]] [])))

(rf/reg-event-fx
 ::browse-select-facet
 (fn [{:keys [db]} [_ facet]]
   {:db           (assoc db
                         :active-page :browse
                         :browse-facet facet
                         :browse-selection nil
                         :browse-filter ""
                         :browse-media-page 1)
    :push-history (routes/browse-path facet)
    :dispatch     [::load-browse-facet facet]}))

(rf/reg-event-fx
 ::browse-select-item
 (fn [{:keys [db]} [_ facet value]]
   {:db           (assoc db
                         :active-page :browse
                         :browse-facet facet
                         :browse-selection value
                         :browse-media-page 1)
    :push-history (routes/browse-path facet value)
    :dispatch-n   [[::load-browse-facet facet]
                   [::load-browse-media facet value]]}))

(rf/reg-event-fx
 ::browse-clear-selection
 (fn [{:keys [db]} [_]]
   (let [facet (:browse-facet db :tags)]
     {:db           (assoc db :browse-selection nil :browse-media-page 1)
      :push-history (routes/browse-path facet)
      :dispatch     [::load-browse-facet facet]})))

(rf/reg-event-db
 ::set-browse-filter
 (fn [db [_ text]]
   (assoc db :browse-filter text)))

(rf/reg-event-db
 ::set-browse-media-page
 (fn [db [_ page]]
   (assoc db :browse-media-page page)))

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
   (log-request-failure "Failed to load channels:" response)
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

(rf/reg-event-fx
 ::load-channel-events-success
 (fn [{:keys [db]} [_ channel-id response]]
   (let [body      (:body response)
         items     (if (map? body) (:items body) body)
         ;; Resolve names for the content items referenced by this playout so
         ;; the guide can show titles and link to each media item.
         media-ids (->> items
                        (filter #(#{nil "content"} (:kind %)))
                        (keep :media-item-id)
                        distinct)]
     {:db         (-> db
                      (assoc-in [:channel-events channel-id] items)
                      (update :channel-events-loading disj channel-id))
      :dispatch-n (mapv (fn [id] [::ensure-media-item id]) media-ids)})))

(rf/reg-event-db
 ::load-channel-events-failure
 (fn [db [_ channel-id response]]
   (log-request-failure (str "Failed to load channel events: " channel-id) response)
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
      :dispatch-n   (cond-> [[::load-jobs] [::poll-channel-playout-job channel-id]]
                      need-channels? (conj [::load-channels])
                      need-events?   (conj [::load-channel-events channel-id]))})))

;; ---------------------------------------------------------------------------
;; Jobs
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::load-jobs
 (fn [{:keys [db]} _]
   {:db (assoc db :jobs-loading #{:tunarr-scheduler :pseudovision})
    :dispatch-n [[::martian/request
                  :get-api-jobs
                  {::martian/instance-id :tunarr-scheduler}
                  [::load-jobs-success :tunarr-scheduler]
                  [::load-jobs-failure :tunarr-scheduler]]
                 [::martian/request
                  :get-api-jobs
                  {::martian/instance-id :pseudovision}
                  [::load-jobs-success :pseudovision]
                  [::load-jobs-failure :pseudovision]]]}))

(rf/reg-event-db
 ::load-jobs-success
 (fn [db [_ source response]]
   (let [body (:body response)
         jobs (if (map? body) (or (:jobs body) (vals body)) body)]
     (-> db
         (assoc-in [:jobs-by-source source] (vec (or jobs [])))
         (update :jobs-loading disj source)))))

(rf/reg-event-db
 ::load-jobs-failure
 (fn [db [_ source response]]
   (log-request-failure (str "Failed to load " (name source) " jobs:") response)
   (-> db
       (assoc-in [:jobs-by-source source] [])
       (update :jobs-loading disj source))))

(defn- job-channel-id
  "The channel a job belongs to, for jobs that operate on a single channel
  (e.g. Pseudovision playout generation). Checked at the top level first,
  falling back to :metadata since that's where Tunarr-Scheduler-style jobs
  tuck extra context."
  [{:keys [metadata] :as job}]
  (or (:channel-id job) (:channel-id metadata)))

(defn- channel-playout-job-active?
  "True if Pseudovision currently has a running/queued playout-generation
  job for the given channel."
  [db channel-id]
  (let [jobs (mapcat (fn [[source jobs]] (map #(assoc % :source source) jobs))
                     (:jobs-by-source db))]
    (boolean
     (some #(and (= :pseudovision (:source %))
                 (contains? #{:running :pending :queued} (keyword (:status %)))
                 (= (str (job-channel-id %)) (str channel-id)))
           jobs))))

(rf/reg-event-fx
 ::poll-channel-playout-job
 (fn [{:keys [db]} [_ channel-id grace]]
   (let [grace (or grace 3)]
     (when (and (= (:active-page db) :channel-schedule)
                (= (:current-channel-id db) channel-id)
                (or (pos? grace) (channel-playout-job-active? db channel-id)))
       {:dispatch [::load-jobs]
        ::timeout {:ms 3000 :dispatch [::poll-channel-playout-job channel-id (max 0 (dec grace))]}}))))

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
   (update (action-success-fx [:rebuild-playout channel-id] "Playout rebuilt")
           :dispatch-n (fnil conj []) [::poll-channel-playout-job channel-id])))

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

;; ---------------------------------------------------------------------------
;; Tag curation tasks (catalog-wide, async jobs in Tunarr Scheduler)
;;
;;   POST /api/media/tags/audit?dry-run=true
;;     LLM audit of all tags; deletes those recommended for removal unless
;;     dry-run. Report lands in the job result (:removed [{:tag :reason}]).
;;   POST /api/media/tags/triage?dry-run=true&target-limit=N
;;     LLM governance triage with usage counts; applies keep/drop/merge/rename
;;     decisions unless dry-run (:decisions [{:tag :action :replacement
;;     :rationale}]).
;; ---------------------------------------------------------------------------

(rf/reg-event-db
 ::set-tag-task-option
 (fn [db [_ k v]]
   (assoc-in db [:tag-task-options k] v)))

(rf/reg-event-fx
 ::trigger-tag-audit
 (fn [{:keys [db]} _]
   (let [{:keys [dry-run]} (:tag-task-options db)]
     {:db       (assoc-in db [:action-states :tag-audit] {:status :loading})
      :dispatch [::martian/request
                 :post-api-media-tags-audit
                 {::martian/instance-id :tunarr-scheduler
                  :dry-run (str (boolean dry-run))}
                 [::trigger-tag-audit-success dry-run]
                 [::trigger-tag-audit-failure]]})))

(rf/reg-event-fx
 ::trigger-tag-audit-success
 (fn [_ [_ dry-run _response]]
   (action-success-fx :tag-audit (if dry-run "Audit started (dry run)" "Audit started"))))

(rf/reg-event-fx
 ::trigger-tag-audit-failure
 (fn [_ [_ response]]
   (action-error-fx :tag-audit response)))

(rf/reg-event-fx
 ::trigger-tag-triage
 (fn [{:keys [db]} _]
   (let [{:keys [dry-run target-limit]} (:tag-task-options db)]
     {:db       (assoc-in db [:action-states :tag-triage] {:status :loading})
      :dispatch [::martian/request
                 :post-api-media-tags-triage
                 (cond-> {::martian/instance-id :tunarr-scheduler
                          :dry-run (str (boolean dry-run))}
                   target-limit (assoc :target-limit target-limit))
                 [::trigger-tag-triage-success dry-run]
                 [::trigger-tag-triage-failure]]})))

(rf/reg-event-fx
 ::trigger-tag-triage-success
 (fn [_ [_ dry-run _response]]
   (action-success-fx :tag-triage (if dry-run "Triage started (dry run)" "Triage started"))))

(rf/reg-event-fx
 ::trigger-tag-triage-failure
 (fn [_ [_ response]]
   (action-error-fx :tag-triage response)))

;; ---------------------------------------------------------------------------
;; Collections (localStorage-backed)
;; ---------------------------------------------------------------------------

(def ^:private collections-storage-key "marquee-collections")

(defn- save-collections! [collections]
  (.setItem js/localStorage collections-storage-key
            (js/JSON.stringify (clj->js collections))))

(defn- load-collections []
  (when-let [raw (.getItem js/localStorage collections-storage-key)]
    (try
      (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
        (into {} (map (fn [[k v]] [(name k) (assoc v :id (name k))])) parsed))
      (catch :default _ {}))))

(rf/reg-event-fx
 ::load-collections
 (fn [{:keys [db]} _]
   (let [colls      (or (load-collections) {})
         db         (assoc db :collections colls)
         coll-id    (:current-collection-id db)
         items      (when coll-id (get-in colls [coll-id :items]))
         dispatches (when (seq items)
                      (mapv (fn [mid] [::load-media-item mid]) items))]
     (cond-> {:db db}
       (seq dispatches) (assoc :dispatch-n dispatches)))))

(rf/reg-event-db
 ::set-new-collection-name
 (fn [db [_ name]]
   (assoc db :new-collection-name name)))

(rf/reg-event-db
 ::create-collection
 (fn [db _]
   (let [name (get db :new-collection-name "")]
     (if (clojure.string/blank? name)
       db
       (let [id    (str (random-uuid))
             coll  {:id id :name name :items [] :created-at (.getTime (js/Date.))}
             colls (assoc (:collections db) id coll)]
         (save-collections! colls)
         (assoc db :collections colls :new-collection-name ""))))))

(rf/reg-event-fx
 ::delete-collection
 (fn [{:keys [db]} [_ collection-id]]
   (let [colls (dissoc (:collections db) collection-id)]
     (save-collections! colls)
     {:db           (assoc db :collections colls :current-collection-id nil)
      :push-history (routes/page->path :collections)})))

(rf/reg-event-db
 ::add-to-collection
 (fn [db [_ collection-id media-id]]
   (let [media-id (if (number? media-id) media-id (js/parseInt media-id))
         colls (update-in (:collections db) [collection-id :items]
                          (fn [items]
                            (if (some #{media-id} items)
                              items
                              (conj (vec items) media-id))))]
     (save-collections! colls)
     (assoc db :collections colls :add-to-collection-open? false))))

(rf/reg-event-db
 ::remove-from-collection
 (fn [db [_ collection-id media-id]]
   (let [colls (update-in (:collections db) [collection-id :items]
                          (fn [items] (vec (remove #{media-id} items))))]
     (save-collections! colls)
     (assoc db :collections colls))))

(rf/reg-event-db
 ::toggle-add-to-collection
 (fn [db _]
   (update db :add-to-collection-open? not)))

(rf/reg-event-db
 ::close-add-to-collection
 (fn [db _]
   (assoc db :add-to-collection-open? false)))

(rf/reg-event-fx
 ::navigate-to-collection
 (fn [{:keys [db]} [_ collection-id]]
   (let [coll   (get-in db [:collections collection-id])
         items  (:items coll)
         dispatches (mapv (fn [mid] [::load-media-item mid]) items)]
     {:db           (-> db
                        (assoc :active-page :collection-detail)
                        (assoc :current-collection-id collection-id))
      :push-history (routes/collection-path collection-id)
      :dispatch-n   dispatches})))

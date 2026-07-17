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

;; Keep in sync with marquee.pages.schedule/grid-window-ms (the guide's visible
;; window); the Back/Forward controls step by exactly one window.
(def ^:private grid-window-ms (* 3 60 60 1000))

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   {:active-page :home
    :media-libraries nil
    :media-items {}
    :media-children {}           ; media-id → {:items :total :has-more} | false
    :scheduler-metadata {}
    :current-media-id nil
    :selected-library-id nil
    :media-page-items nil        ; current page of the selected library's items
    :media-total nil             ; total items matching the current query (nil = unknown)
    :media-has-more nil          ; server flag: more pages after the current one
    :media-loading? false
    :media-current-page 1
    :media-page-size 20
    :media-filter ""             ; server-side text search over the selected library
    :media-search-token 0        ; debounce token for the filter input
    ;; Media-tab source switch: :library (Pseudovision libraries) | :grout.
    :media-source :library
    :grout-collections nil       ; nil=loading | :error | vector of directory profiles
    :grout-collection nil        ; selected collection tag (parent-directory:x), or nil
    :grout-media {}              ; collection-tag → {:status :items :count} | {:status :loading}
    :grout-media-page 1
    :grout-kind nil              ; drill-down filter: nil | "bumper" | "filler" | "program"
    :grout-filter ""             ; client-side text filter over the current view
    :grout-item nil              ; open item detail: nil | {:status … :item …}
    :current-grout-id nil
    :jellyfin-url nil
    ;; Browse-by-metadata state (Tunarr Scheduler browse endpoints)
    :browse-facet :tags          ; :tags | :dimensions
    :browse-selection nil        ; selected tag or dimension:value, or nil
    :browse-lists {}             ; facet → vector of facet entries
    :browse-media {}             ; [facet value] → vector of media items
    :browse-dimension nil        ; selected dimension name for value drill-down
    :dimension-values {}         ; dimension name → vector of values
    :browse-media-page 1
    :browse-filter ""
    :api-specs {}
    :api-selected-service nil
    :api-expanded-ops #{}
    :api-filter ""
    ;; Backstop for `::subs/api-ready?`: a soft-disabled or unreachable service
    ;; never loads its martian spec, so a timeout flips readiness to render the
    ;; app anyway rather than hanging forever on the loading screen.
    :api-force-ready? false
    ;; Schedule / guide state
    :channels nil
    :channels-loading? false
    :channel-events {}           ; channel-id → [PlayoutEvent ...]
    :channel-events-loading #{}  ; set of channel-ids currently loading
    :schedule-window-start (.getTime (js/Date.))
    :current-channel-id nil
    :channel-guidance {}         ; channel-slug → :loading | false | {:guidance <str|nil>}
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
    :collection-filter ""        ; text filter over a collection's items
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
                     (when (= page :home)
                       [[::load-channels] [::load-jobs]])
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
                              ;; Top-nav "Media" always lands on the library source.
                              (= page :media) (assoc :media-source :library)
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
    ;; which we only know once the Pseudovision item arrives. The dimensions
    ;; list feeds the category editor's dimension picker (cached, so cheap).
    :dispatch-n   [[::load-media-item media-id]
                   [::load-browse-facet :dimensions]]}))

(rf/reg-event-fx
 ::restore-from-url
 (fn [{:keys [db]} [_ path]]
   (let [{:keys [page media-id channel-id collection-id facet selection source]}
         (or (routes/parse-path path) {:page :home})
         dispatches (concat
                     (when (= page :home)
                       [[::load-channels] [::load-jobs]])
                     (when (and (= page :media) (= source :grout))
                       [[::load-grout-collections]])
                     (when (and (= page :media) (not= source :grout))
                       [[::load-media-libraries] [::set-media-page 1]])
                     (when (and (= page :api-docs) (nil? (:api-selected-service db)))
                       [[::select-api-service :pseudovision]])
                     (when (= page :media-detail)
                       [[::load-media-item media-id]
                        [::load-browse-facet :dimensions]])
                     (when (= page :grout-detail)
                       [[::load-grout-item media-id]])
                     (when (= page :browse)
                       (cond-> [[::load-browse-facet (or facet :tags)]]
                         selection (conj (if (and (= facet :dimensions)
                                                  (not (clojure.string/includes? selection ":")))
                                           [::load-dimension-values selection]
                                           [::load-browse-media (or facet :tags) selection]))))
                     (when (= page :schedule-grid)
                       [[::load-channels]])
                     (when (= page :channel-schedule)
                       (cond-> (conj (if (nil? (:channels db)) [[::load-channels]] [])
                                     [::load-ffmpeg-profiles])
                         channel-id (conj [::load-channel-events channel-id])))
                     (when (= page :jobs)
                       [[::load-jobs]])
                     (when (#{:collections :collection-detail} page)
                       [[::load-collections]]))]
     (cond-> {:db (cond-> (assoc db :active-page page)
                    (= page :media)             (assoc :media-source (or source :library))
                    (= page :media-detail)      (assoc :current-media-id media-id)
                    (= page :grout-detail)      (assoc :current-grout-id media-id)
                    (= page :browse)            (assoc :browse-facet (or facet :tags)
                                                       :browse-media-page 1)
                    (and (= page :browse) selection (or (not= facet :dimensions) (clojure.string/includes? selection ":")))
                    (assoc :browse-selection selection)
                    (and (= page :browse) (= facet :dimensions) selection (not (clojure.string/includes? selection ":")))
                    (assoc :browse-dimension selection)
                    (= page :channel-schedule)  (assoc :current-channel-id channel-id)
                    (= page :collection-detail) (assoc :current-collection-id collection-id))}
       (seq dispatches) (assoc :dispatch-n dispatches)))))

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

;; Query-param the backend exposes for case-insensitive title search on
;; /api/media/libraries/{id}/items. Martian only sends params declared in the
;; OpenAPI spec, so this must match the backend's parameter name exactly.
(def ^:private media-search-param :search)

;; BFF URL for a library's items, with pagination + search as an explicit query
;; string. The path mirrors the martian operation
;; `get-api-media-libraries-id-items` (i.e. Pseudovision's
;; /api/media/libraries/{id}/items) behind the BFF's /api/pseudovision prefix.
(defn- library-items-url [library-id {:keys [limit offset search]}]
  (str "/api/pseudovision/api/media/libraries/" library-id "/items"
       "?limit=" limit "&offset=" offset
       (when-not (clojure.string/blank? search)
         (str "&" (name media-search-param) "=" (js/encodeURIComponent search)))))

;; Fetch a single page of the selected library's items from the server. Paging
;; (limit/offset) and title search (the `search` param) are both done
;; server-side, so we only ever hold one page in memory — large libraries no
;; longer hang the UI. The response's :pagination map reports kebab-case
;; :total (count of matching items) and :has-more.
;;
;; This goes straight to the BFF rather than through martian: martian coerces
;; query params against the operation's OpenAPI :query-schema and DROPS any it
;; doesn't declare, so if the spec omits limit/offset the pager silently
;; refetches offset 0 forever ("Next" repeats page 1). A plain fetch guarantees
;; the params reach the backend, which forwards the query string verbatim.
(rf/reg-event-fx
 ::load-library-items
 (fn [{:keys [db]} [_ library-id]]
   (let [page      (:media-current-page db 1)
         page-size (:media-page-size db 20)
         needle    (:media-filter db "")]
     {:db          (assoc db :media-loading? true)
      ::fetch-json {:url         (library-items-url library-id
                                                    {:limit  page-size
                                                     :offset (* (dec page) page-size)
                                                     :search needle})
                    :keywordize? true
                    :on-success  [::load-library-items-success library-id]
                    :on-failure  [::load-library-items-failure library-id]}})))

(rf/reg-event-db
 ::load-library-items-success
 ;; `body` is the parsed JSON (keywordized): the paginated envelope, with a
 ;; fallback for the pre-pagination (plain array) format.
 (fn [db [_ _library-id body]]
   (let [items      (vec (if (map? body) (:items body) body))
         pagination (when (map? body) (:pagination body))]
     (-> db
         (assoc :media-page-items items)
         (assoc :media-total (:total pagination))
         (assoc :media-has-more (:has-more pagination))
         (assoc :media-loading? false)))))

(rf/reg-event-db
 ::load-library-items-failure
 (fn [db [_ library-id error]]
   (js/console.error "Failed to load library items:" library-id error)
   (-> db
       (assoc :media-page-items [])
       (assoc :media-total 0)
       (assoc :media-has-more false)
       (assoc :media-loading? false))))

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
         numeric-id (:id item)
         remote-key (:remote-key item)
         parent-id  (:parent-id item)
         ;; Inherited attributes and the children list are only needed for the
         ;; item currently open on the detail page. Shared loaders (collections,
         ;; guide) also hit this handler and don't want the extra requests.
         detail?    (= media-id (:current-media-id db))
         ;; Tunarr Scheduler keys its catalog by Pseudovision's numeric id.
         ;; Grounding context is only surfaced on the detail page and needs the
         ;; remote-key, so it's fetched only when both hold.
         dispatches (cond-> [[::load-media-tags numeric-id]]
                      remote-key               (conj [::load-scheduler-metadata media-id remote-key])
                      (and detail? remote-key) (conj [::load-media-context media-id remote-key])
                      (and detail? parent-id)  (conj [::load-media-ancestors parent-id])
                      detail?                  (conj [::load-media-children media-id]))]
     {:db (cond-> (assoc-in db [:media-items media-id] item)
            (not remote-key) (assoc-in [:scheduler-metadata media-id] false))
      :dispatch-n dispatches})))

(rf/reg-event-db
 ::load-media-item-failure
 (fn [db [_ media-id response]]
   (log-request-failure (str "Failed to load media item: " media-id) response)
   (assoc-in db [:media-items media-id] false)))

;; Walk an item's parent chain (episode → season → show), loading each ancestor
;; along with its tags and scheduler categories so the detail page can surface
;; attributes inherited from parents (e.g. a show's tags on a "special episode").
;; Ancestors are cached under their Pseudovision id; already-loaded parents are
;; skipped so navigating between siblings doesn't refetch the shared chain.
(rf/reg-event-fx
 ::load-media-ancestors
 (fn [{:keys [db]} [_ parent-id]]
   (if (or (nil? parent-id) (contains? (:media-items db) parent-id))
     {:db db}
     {:db db
      :dispatch [::martian/request
                 :get-api-media-items-id
                 {::martian/instance-id :pseudovision
                  :id parent-id}
                 [::load-media-ancestors-success parent-id]
                 [::load-media-ancestors-failure parent-id]]})))

(rf/reg-event-fx
 ::load-media-ancestors-success
 (fn [{:keys [db]} [_ parent-id response]]
   (let [item        (:body response)
         numeric-id  (:id item)
         remote-key  (:remote-key item)
         next-parent (:parent-id item)]
     {:db (assoc-in db [:media-items parent-id] item)
      :dispatch-n (cond-> [[::load-media-tags numeric-id]]
                    remote-key  (conj [::load-scheduler-metadata parent-id remote-key])
                    next-parent (conj [::load-media-ancestors next-parent]))})))

(rf/reg-event-db
 ::load-media-ancestors-failure
 (fn [db [_ parent-id response]]
   (log-request-failure (str "Failed to load parent media item: " parent-id) response)
   db))

;; Direct children of a media item (show → seasons, season → episodes). Capped
;; at one page so shows / YouTube channels with thousands of children aren't
;; pulled in wholesale; the response's :pagination reports the true :total so
;; the detail page can note how many more exist. Requires the Pseudovision
;; endpoint GET /api/media/items/{id}/children — until that ships the request
;; 404s and the children section stays hidden.
(def ^:private media-children-limit 50)

(rf/reg-event-fx
 ::load-media-children
 (fn [{:keys [db]} [_ media-id]]
   {:db db
    :dispatch [::martian/request
               :get-api-media-items-id-children
               {::martian/instance-id :pseudovision
                :id     media-id
                :limit  media-children-limit
                :offset 0}
               [::load-media-children-success media-id]
               [::load-media-children-failure media-id]]}))

(rf/reg-event-db
 ::load-media-children-success
 (fn [db [_ media-id response]]
   (let [body       (:body response)
         ;; Same paginated envelope as /libraries/{id}/items, with a fallback
         ;; for a bare-array response.
         items      (vec (if (map? body) (:items body) body))
         pagination (when (map? body) (:pagination body))]
     (assoc-in db [:media-children media-id]
               {:items    items
                :total    (:total pagination)
                :has-more (:has-more pagination)
                :limit    media-children-limit}))))

(rf/reg-event-db
 ::load-media-children-failure
 (fn [db [_ media-id response]]
   ;; The children endpoint is optional; a 404 just means this Pseudovision
   ;; build doesn't expose it yet, so keep this quiet rather than error-logging.
   (js/console.debug "No children available for media item" media-id
                     (str "(status " (:status response) ")"))
   (assoc-in db [:media-children media-id] false)))

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

(rf/reg-event-fx
 ::ensure-media-item-success
 (fn [{:keys [db]} [_ media-id response]]
   (let [item      (:body response)
         parent-id (:parent-id item)]
     ;; Pull in the parent chain too so callers (e.g. the guide) can render a
     ;; descriptive "Episode - Season - Show" name. ensure-media-item dedupes,
     ;; so shared ancestors are fetched once.
     {:db (-> db
              (assoc-in [:media-items media-id] item)
              (update :media-items-loading disj media-id))
      :dispatch-n (when parent-id [[::ensure-media-item parent-id]])})))

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
   ;; Tunarr Scheduler keys its catalog by Jellyfin ID (remote-key), not Pseudovision's numeric id.
   {:db db
    :dispatch [::martian/request
               :get-api-media-item-media-id
               {::martian/instance-id :tunarr-scheduler
                :media-id remote-key}
               [::load-scheduler-metadata-success media-id remote-key]
               [::load-scheduler-metadata-failure media-id]]}))

(rf/reg-event-fx
 ::load-scheduler-metadata-success
 (fn [{:keys [db]} [_ media-id remote-key response]]
   (let [metadata      (:body response)
         ;; Fallback: extract dimension data from the scheduler metadata itself
         ;; when the dedicated /categories endpoint is broken.
         fallback-cats (when (map? metadata)
                         (->> (select-keys metadata
                                           [:tunarr.scheduler.media/tags
                                            :tunarr.scheduler.media/genres
                                            :tunarr.scheduler.media/channel-names])
                              (map (fn [[k v]]
                                     [(case k
                                        :tunarr.scheduler.media/tags        "tag"
                                        :tunarr.scheduler.media/genres      "genre"
                                        :tunarr.scheduler.media/channel-names "channel"
                                        (name k))
                                      v]))
                              (into {})))
         ;; Scheduler is the source of truth for tags; seed the tag editor's set
         ;; from the metadata blob. Tag mutations overwrite this from their
         ;; (authoritative) response, so a wrong/absent key self-corrects on the
         ;; first edit.
         ts-tags       (when (map? metadata) (:tunarr.scheduler.media/tags metadata))]
     {:db (cond-> (-> db
                      (assoc-in [:scheduler-metadata media-id] metadata)
                      (assoc-in [:media-categories media-id] (or fallback-cats
                                                                 (get-in db [:media-categories media-id]))))
            (some? ts-tags) (assoc-in [:media-item-tags media-id] (vec ts-tags)))
      :dispatch [::load-media-categories media-id remote-key]})))

(rf/reg-event-db
 ::load-scheduler-metadata-failure
 (fn [db [_ media-id response]]
   (log-request-failure (str "Failed to load scheduler metadata: " media-id) response)
   (assoc-in db [:scheduler-metadata media-id] false)))

(rf/reg-event-fx
 ::load-media-categories
 (fn [{:keys [db]} [_ media-id remote-key]]
   {:db db
    :dispatch [::martian/request
               :get-api-media-media-id-categories
               {::martian/instance-id :tunarr-scheduler
                :media-id remote-key}
               [::load-media-categories-success media-id]
               [::load-media-categories-failure media-id]]}))

(rf/reg-event-db
 ::load-media-categories-success
 (fn [db [_ media-id response]]
   (assoc-in db [:media-categories media-id] (get-in response [:body :categories]))))

(rf/reg-event-db
 ::load-media-categories-failure
 (fn [db [_ media-id response]]
   (log-request-failure (str "Failed to load media categories: " media-id) response)
   ;; Preserve fallback categories extracted from scheduler metadata if present.
   (update-in db [:media-categories media-id]
              (fn [existing]
                (if (map? existing)
                  existing
                  false)))))

;; Library selection and pagination events

;; Switching libraries resets paging/filter and fetches the first page. We clear
;; the held page so the view shows a loading state instead of the prior
;; library's items.
(rf/reg-event-fx
 ::select-library
 (fn [{:keys [db]} [_ library-id]]
   {:db       (-> db
                  (assoc :selected-library-id library-id)
                  (assoc :media-current-page 1)
                  (assoc :media-filter "")
                  (assoc :media-page-items nil)
                  (assoc :media-total nil)
                  (assoc :media-has-more nil))
    :dispatch [::load-library-items library-id]}))

(rf/reg-event-fx
 ::set-media-page
 (fn [{:keys [db]} [_ page]]
   ;; Only fetch when a library is selected — on first navigation the page is
   ;; reset before any library exists, and select-library handles that load.
   (cond-> {:db (assoc db :media-current-page page)}
     (:selected-library-id db)
     (assoc :dispatch [::load-library-items (:selected-library-id db)]))))

(rf/reg-event-fx
 ::set-media-page-size
 (fn [{:keys [db]} [_ size]]
   (cond-> {:db (-> db
                    (assoc :media-page-size size)
                    (assoc :media-current-page 1))}
     (:selected-library-id db)
     (assoc :dispatch [::load-library-items (:selected-library-id db)]))))

;; Filtering is server-side. We store the needle immediately (so the input stays
;; responsive), reset to page 1, and debounce the actual request: each keystroke
;; bumps a token and schedules a fetch that only fires if it's still the latest.
(rf/reg-event-fx
 ::set-media-filter
 (fn [{:keys [db]} [_ text]]
   (let [token (inc (:media-search-token db 0))]
     {:db       (-> db
                    (assoc :media-filter text)
                    (assoc :media-current-page 1)
                    (assoc :media-search-token token))
      ::timeout {:ms 300 :dispatch [::run-media-search token]}})))

(rf/reg-event-fx
 ::run-media-search
 (fn [{:keys [db]} [_ token]]
   ;; Drop stale debounce timers — only the most recent keystroke fetches.
   (when (and (= token (:media-search-token db))
              (:selected-library-id db))
     {:dispatch [::load-library-items (:selected-library-id db)]})))

;; ---------------------------------------------------------------------------
;; Browse by metadata (Tunarr Scheduler browse endpoints)
;;
;;   GET /api/tags                                  → {:tags [{:tag :usage-count :example-titles}]}
;;   GET /api/tags/:tag/media                       → {:media [...]}
;;   GET /api/dimensions                            → {:dimensions [{:name :value-count}]}
;;   GET /api/dimensions/:dim/values                → {:values [...]}
;;   GET /api/media/:id/categories                  → {:categories {dim → [value ...]}}
;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------

(defn- key-name [k]
  (if (keyword? k) (name k) (str k)))

(defn- strip-key-namespaces [m]
  (into {} (map (fn [[k v]] [(keyword (key-name k)) v])) m))

(defn- browse-list-op [facet]
  (case facet
    :tags       :get-api-tags
    :dimensions :get-api-dimensions))

(defn- browse-media-request [facet value]
  (case facet
    :tags       [:get-api-tags-tag-media {:tag value}]
    :dimensions (let [[dimension dim-value] (clojure.string/split value #":" 2)]
                  [:get-api-dimensions-dimension-values-value-media 
                   {:dimension dimension :value dim-value}])))

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
                 :tags       (:tags body)
                 :dimensions (:dimensions body))]
     (assoc-in db [:browse-lists facet] (vec items)))))

(rf/reg-event-fx
 ::load-browse-facet-failure
 (fn [{:keys [db]} [_ facet response]]
   (log-request-failure (str "Failed to load browse facet: " (name facet)) response)
   (if (= facet :dimensions)
     {:db (assoc-in db [:browse-lists :dimensions] [])
      :dispatch [::load-genres-as-dimensions]}
      {:db (assoc-in db [:browse-lists facet] [])})))

;; Fallback: when /api/dimensions is broken, load the genres list and
;; surface it as a single synthetic dimension so the facet is not empty.
(rf/reg-event-fx
 ::load-genres-as-dimensions
 (fn [{:keys [db]} _]
   {:db db
    :dispatch [::martian/request
               :get-api-genres
               {::martian/instance-id :tunarr-scheduler}
               [::load-genres-as-dimensions-success]
               [::load-genres-as-dimensions-failure]]}))

(rf/reg-event-db
 ::load-genres-as-dimensions-success
 (fn [db [_ response]]
   (let [genres (get-in response [:body :genres])]
     (-> db
         (assoc-in [:browse-lists :dimensions]
                   [{:name "genre" :value-count (count genres)}])
         (assoc-in [:dimension-values "genre"] (vec genres))))))

(rf/reg-event-db
 ::load-genres-as-dimensions-failure
 (fn [db [_ response]]
   (log-request-failure "Failed to load genres as fallback:" response)
   (assoc-in db [:browse-lists :dimensions] [])))

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
                         :browse-dimension nil
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
     {:db           (assoc db :browse-selection nil :browse-dimension nil :browse-media-page 1)
      :push-history (routes/browse-path facet)
      :dispatch     [::load-browse-facet facet]})))

(rf/reg-event-fx
 ::browse-select-dimension
 (fn [{:keys [db]} [_ dim-name]]
   {:db           (assoc db
                         :active-page :browse
                         :browse-facet :dimensions
                         :browse-dimension dim-name
                         :browse-selection nil
                         :browse-media-page 1)
    :push-history (routes/browse-path :dimensions)
    :dispatch     [::load-dimension-values dim-name]}))

(rf/reg-event-fx
 ::load-dimension-values
 (fn [{:keys [db]} [_ dim-name]]
   (if (get-in db [:dimension-values dim-name])
     {:db db}
     {:db db
      :dispatch [::martian/request
                 :get-api-dimensions-dimension-values
                 {::martian/instance-id :tunarr-scheduler
                  :dimension dim-name}
                 [::load-dimension-values-success dim-name]
                 [::load-dimension-values-failure dim-name]]})))

(rf/reg-event-db
 ::load-dimension-values-success
 (fn [db [_ dim-name response]]
   (assoc-in db [:dimension-values dim-name] (get-in response [:body :values]))))

(rf/reg-event-db
 ::load-dimension-values-failure
 (fn [db [_ dim-name response]]
   (log-request-failure (str "Failed to load dimension values: " dim-name) response)
   (assoc-in db [:dimension-values dim-name] [])))

(rf/reg-event-db
 ::set-browse-filter
 (fn [db [_ text]]
   (assoc db :browse-filter text)))

(rf/reg-event-db
 ::set-browse-media-page
 (fn [db [_ page]]
   (assoc db :browse-media-page page)))

;; API documentation browser events

;; Plain fetch of JSON from the BFF. Defaults to string keys (used for the
;; OpenAPI specs and /api/config); pass :keywordize? true to get kebab-case
;; keyword keys, e.g. for proxied data endpoints consumed like martian results.
(rf/reg-fx
 ::fetch-json
 (fn [{:keys [url on-success on-failure keywordize?]}]
   (-> (js/fetch url)
       (.then (fn [resp]
                (if (.-ok resp)
                  (.json resp)
                  (throw (js/Error. (str "HTTP " (.-status resp) " " (.-statusText resp)))))))
       (.then (fn [data] (rf/dispatch (conj on-success (js->clj data :keywordize-keys (boolean keywordize?))))))
       (.catch (fn [err] (rf/dispatch (conj on-failure (.-message err))))))))

;; Mutating HTTP (PUT/POST/DELETE) through the BFF, for endpoints not modelled
;; in martian. Sends `body` as JSON when present and tolerates empty/204
;; responses (on-success receives the HTTP status; the body isn't parsed).
(rf/reg-fx
 ::http-mutate
 (fn [{:keys [url method body on-success on-failure]}]
   (-> (js/fetch url (clj->js (cond-> {:method (or method "POST")}
                                (some? body)
                                (assoc :headers {"Content-Type" "application/json"}
                                       :body (js/JSON.stringify (clj->js body))))))
       (.then (fn [resp]
                (if (.-ok resp)
                  ;; Hand the parsed JSON body (keywordized; nil for empty/204
                  ;; responses) to on-success, so callers can use the server's
                  ;; post-change state without a follow-up GET.
                  (.then (.text resp)
                         (fn [t]
                           (let [parsed (when-not (or (nil? t) (= t ""))
                                          (try (js->clj (js/JSON.parse t) :keywordize-keys true)
                                               (catch :default _ nil)))]
                             (rf/dispatch (conj on-success parsed)))))
                  (throw (js/Error. (str "HTTP " (.-status resp) " " (.-statusText resp)))))))
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

;; Backstop dispatched on a timer at startup (see marquee.core): forces the app
;; past the loading gate so an unreachable service can't strand the whole UI.
(rf/reg-event-db
 ::force-api-ready
 (fn [db _]
   (assoc db :api-force-ready? true)))

;; --- Grout media source ----------------------------------------------------
;; Grout has its own tag semantics (parent-directory collections, channel and
;; content-type namespaces), so it gets its own view under the Media tab rather
;; than sharing the library browser. Data is fetched straight through the BFF
;; (`/api/grout/...`) with kebab-case keys, matching Grout's JSON convention.

(rf/reg-event-fx
 ::set-media-source
 (fn [{:keys [db]} [_ source]]
   ;; Also lands on the Media page, so this doubles as "back to Grout" from the
   ;; item detail page (active-page :grout-detail).
   {:db           (assoc db :active-page :media :media-source source :grout-filter "")
    :push-history (if (= source :grout) "/media/grout" "/media")
    :dispatch-n   (case source
                    :grout   [[::load-grout-collections]]
                    :library [[::load-media-libraries] [::set-media-page 1]]
                    [])}))

(rf/reg-event-fx
 ::load-grout-collections
 (fn [{:keys [db]} _]
   ;; Only fetch once; the Collections index rarely changes within a session.
   (if (vector? (:grout-collections db))
     {:db db}
     {:db          (assoc db :grout-collections nil)
      ::fetch-json {:url         "/api/grout/grout/directory-profiles"
                    :keywordize? true
                    :on-success  [::load-grout-collections-success]
                    :on-failure  [::load-grout-collections-failure]}})))

(rf/reg-event-db
 ::load-grout-collections-success
 (fn [db [_ resp]]
   (assoc db :grout-collections (vec (:profiles resp)))))

(rf/reg-event-db
 ::load-grout-collections-failure
 (fn [db [_ err]]
   (js/console.error "Failed to load Grout collections:" err)
   (assoc db :grout-collections :error)))

(rf/reg-event-fx
 ::open-grout-collection
 (fn [{:keys [db]} [_ tag]]
   {:db       (assoc db :grout-collection tag :grout-media-page 1
                     :grout-kind nil :grout-filter "")
    :dispatch [::load-grout-media tag]}))

(rf/reg-event-db
 ::close-grout-collection
 (fn [db _]
   (assoc db :grout-collection nil :grout-filter "")))

;; Loads a generous page of a collection's media and paginates it client-side
;; (mirrors the Browse page). Grout's query is tag-AND, so we filter by the
;; collection's parent-directory tag; kind is refined server-side when set.
(rf/reg-event-fx
 ::load-grout-media
 (fn [{:keys [db]} [_ tag]]
   (let [kind (:grout-kind db)
         qs   (cond-> (str "tags=" (js/encodeURIComponent tag) "&limit=500&offset=0")
                kind (str "&kind=" (js/encodeURIComponent kind)))]
     {:db          (assoc-in db [:grout-media tag] {:status :loading})
      ::fetch-json {:url         (str "/api/grout/grout/media?" qs)
                    :keywordize? true
                    :on-success  [::load-grout-media-success tag]
                    :on-failure  [::load-grout-media-failure tag]}})))

(rf/reg-event-db
 ::load-grout-media-success
 (fn [db [_ tag resp]]
   (assoc-in db [:grout-media tag] {:status :loaded
                                    :items  (vec (:items resp))
                                    :count  (:count resp)})))

(rf/reg-event-db
 ::load-grout-media-failure
 (fn [db [_ tag err]]
   (js/console.error "Failed to load Grout media:" err)
   (assoc-in db [:grout-media tag] {:status :error :error err})))

(rf/reg-event-fx
 ::set-grout-kind
 (fn [{:keys [db]} [_ kind]]
   (let [tag (:grout-collection db)]
     (cond-> {:db (assoc db :grout-kind kind :grout-media-page 1)}
       tag (assoc :dispatch [::load-grout-media tag])))))

(rf/reg-event-db
 ::set-grout-media-page
 (fn [db [_ page]]
   (assoc db :grout-media-page page)))

(rf/reg-event-db
 ::set-grout-filter
 (fn [db [_ text]]
   (assoc db :grout-filter text :grout-media-page 1)))

;; --- Grout item detail + delete --------------------------------------------

(rf/reg-event-fx
 ::navigate-to-grout-detail
 (fn [{:keys [db]} [_ id]]
   {:db           (assoc db :active-page :grout-detail :current-grout-id id)
    :push-history (routes/grout-detail-path id)
    :dispatch     [::load-grout-item id]}))

(rf/reg-event-fx
 ::load-grout-item
 (fn [{:keys [db]} [_ id]]
   {:db          (assoc db :grout-item {:status :loading})
    ::fetch-json {:url         (str "/api/grout/grout/media/" id)
                  :keywordize? true
                  :on-success  [::load-grout-item-success]
                  :on-failure  [::load-grout-item-failure]}}))

(rf/reg-event-db
 ::load-grout-item-success
 (fn [db [_ item]]
   (assoc db :grout-item {:status :loaded :item item})))

(rf/reg-event-db
 ::load-grout-item-failure
 (fn [db [_ err]]
   (js/console.error "Failed to load Grout item:" err)
   (assoc db :grout-item {:status :error :error err})))

;; Soft-delete (supersede): the item drops out of every listing but the file is
;; kept, so the action is reversible server-side. On success we return to the
;; Grout source and refresh the affected collection + the catalog counts.
(rf/reg-event-fx
 ::delete-grout-item
 (fn [_ [_ id]]
   {::http-mutate {:url        (str "/api/grout/grout/media/" id)
                   :method     "DELETE"
                   :on-success [::delete-grout-item-success]
                   :on-failure [::delete-grout-item-failure]}}))

(rf/reg-event-fx
 ::delete-grout-item-success
 (fn [{:keys [db]} _]
   (let [tag (:grout-collection db)]
     {:db           (-> db
                        (assoc :active-page :media :media-source :grout)
                        (dissoc :grout-item :current-grout-id)
                        ;; Force the catalog + collection media to refetch so
                        ;; the deleted item and stale counts disappear.
                        (assoc :grout-collections nil)
                        (update :grout-media dissoc tag))
      :push-history "/media/grout"
      :dispatch-n   (cond-> [[::load-grout-collections]]
                      tag (conj [::load-grout-media tag]))})))

(rf/reg-event-fx
 ::delete-grout-item-failure
 (fn [_ [_ err]]
   (js/console.error "Failed to delete Grout item:" err)
   {}))

;; --- Grout tag editing + enrichment ----------------------------------------
;; Grout owns its own tags; there is no Tunarr Scheduler / remote-key indirection
;; here (Grout items aren't in Jellyfin), so edits go straight to Grout via the
;; BFF. PATCH replaces the whole tag vector, so add/remove compute the new list
;; from the loaded item and PATCH it. The response is the full updated Media,
;; which refreshes the open detail in place; the cached collection listing is
;; dropped so its chips/counts refetch when next viewed.

(defn- grout-patch-tags-fx [id tags]
  {::http-mutate {:url        (str "/api/grout/grout/media/" id)
                  :method     "PATCH"
                  :body       {:tags tags}
                  :on-success [::grout-item-updated]
                  :on-failure [::grout-item-update-failed]}})

(rf/reg-event-fx
 ::add-grout-tag
 (fn [{:keys [db]} [_ id tag]]
   (let [current (vec (get-in db [:grout-item :item :tags] []))
         next    (if (some #{tag} current) current (conj current tag))]
     (grout-patch-tags-fx id next))))

(rf/reg-event-fx
 ::remove-grout-tag
 (fn [{:keys [db]} [_ id tag]]
   (let [current (vec (get-in db [:grout-item :item :tags] []))
         next    (vec (remove #{tag} current))]
     (grout-patch-tags-fx id next))))

(rf/reg-event-db
 ::grout-item-updated
 (fn [db [_ item]]
   ;; PATCH / enrich return the full updated Media; refresh the open detail and
   ;; drop the cached collection listing so its grid chips/counts refetch.
   (let [tag (:grout-collection db)]
     (cond-> (assoc db :grout-item {:status :loaded :item item})
       tag (update :grout-media dissoc tag)))))

(rf/reg-event-db
 ::grout-item-update-failed
 (fn [db [_ err]]
   (js/console.error "Failed to update Grout item:" err)
   db))

(rf/reg-event-fx
 ::enrich-grout-item
 (fn [{:keys [db]} [_ id]]
   (let [k [:grout-enrich id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str "/api/grout/grout/media/" id "/enrich")
                     :method     "POST"
                     :on-success [::enrich-grout-item-success k]
                     :on-failure [::enrich-grout-item-failure k]}})))

(rf/reg-event-fx
 ::enrich-grout-item-success
 (fn [{:keys [db]} [_ action-key item]]
   (let [tag (:grout-collection db)]
     {:db       (cond-> (assoc db :grout-item {:status :loaded :item item})
                  tag (update :grout-media dissoc tag))
      :dispatch [::set-action-state action-key :success "Enriched"]
      ::timeout {:ms 3000 :dispatch [::clear-action-state action-key]}})))

(rf/reg-event-fx
 ::enrich-grout-item-failure
 (fn [_ [_ action-key err]]
   ;; ::http-mutate hands us an error string; surface it and auto-clear.
   {:dispatch [::set-action-state action-key :error (str err)]
    ::timeout {:ms 5000 :dispatch [::clear-action-state action-key]}}))

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
      :dispatch-n   (cond-> [[::load-jobs] [::poll-channel-playout-job channel-id]
                             [::load-ffmpeg-profiles]]
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

(defn- job-field
  "Looks up `field` on a job, checked at the top level first and falling
  back to :metadata, since async jobs tuck most of their identifying
  context (library, channel, etc.) in there."
  [{:keys [metadata] :as job} field]
  (or (field job) (field metadata)))

(defn- any-job-matches?
  "True if any currently-known job from `source` is running/pending/queued
  and has `field` equal to `value` (compared as strings so numeric ids and
  string names both work)."
  [db source field value]
  (boolean
   (some #(and (= source (:source %))
               (contains? #{:running :pending :queued} (keyword (:status %)))
               (= (str (job-field % field)) (str value)))
         (mapcat (fn [[src jobs]] (map #(assoc % :source src) jobs))
                 (:jobs-by-source db)))))

(rf/reg-event-fx
 ::poll-channel-playout-job
 (fn [{:keys [db]} [_ channel-id grace]]
   (let [grace (or grace 3)]
     (when (and (= (:active-page db) :channel-schedule)
                (= (:current-channel-id db) channel-id)
                (or (pos? grace) (any-job-matches? db :pseudovision :channel-id channel-id)))
       ;; Re-fetch the schedule alongside the job status so the grid picks up
       ;; the newly-generated events as soon as the job finishes, without
       ;; waiting for a manual reload.
       {:dispatch-n [[::load-jobs] [::load-channel-events channel-id]]
        ::timeout   {:ms 3000 :dispatch [::poll-channel-playout-job channel-id (max 0 (dec grace))]}}))))

(defn- library-job-active? [db library-id lib-name]
  (or (any-job-matches? db :pseudovision :library-id library-id)
      (and lib-name (any-job-matches? db :tunarr-scheduler :library lib-name))))

(rf/reg-event-fx
 ::poll-library-job
 (fn [{:keys [db]} [_ library-id lib-name grace]]
   (let [grace (or grace 3)]
     (when (and (= (:active-page db) :media)
                (= (:selected-library-id db) library-id)
                (or (pos? grace) (library-job-active? db library-id lib-name)))
       ;; Re-fetch the library's items alongside the job status so scans,
       ;; retags, etc. show up without a manual reload.
       {:dispatch-n [[::load-jobs] [::load-library-items library-id]]
        ::timeout   {:ms 3000 :dispatch [::poll-library-job library-id lib-name (max 0 (dec grace))]}}))))

(rf/reg-event-fx
 ::poll-channels-after-sync
 ;; Channel sync has no natural per-channel scope to track in the jobs
 ;; list, so this just re-fetches the channel list a few times on a timer
 ;; rather than trying to detect job completion.
 (fn [{:keys [db]} [_ grace]]
   (let [grace (or grace 4)]
     (when (and (= (:active-page db) :schedule-grid) (pos? grace))
       {:dispatch [::load-channels]
        ::timeout {:ms 2000 :dispatch [::poll-channels-after-sync (dec grace)]}}))))

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
                (str "Error " (:status response)))
        trace (get-in response [:body :trace])]
    (when trace
      (js/console.error "Server trace for" (pr-str action-key) "\n" trace))
    {:dispatch   [::set-action-state action-key :error err]
     ::timeout   {:ms 5000 :dispatch [::clear-action-state action-key]}}))

;; ---------------------------------------------------------------------------
;; Channel ffmpeg profiles
;;
;; Lets the channel page switch which transcoding profile a channel uses.
;;
;; API contract (verified against Pseudovision):
;;   1. list profiles: GET /api/ffmpeg/profiles → [ {:id <int> :name :config} … ]
;;   2. a channel's current profile: the :ffmpeg-profile-id field on the channel
;;      object (GET /api/channels/{id}) — see schedule/channel-ffmpeg-profile-id.
;;   3. set a channel's profile: PATCH /api/channels/{id} with JSON body
;;      {:ffmpeg-profile-id <int>}.
;; The requests go straight to the BFF (not martian) so they don't depend on the
;; params/body being declared in the OpenAPI spec — martian silently drops
;; anything the spec omits.
;; ---------------------------------------------------------------------------

(def ^:private ffmpeg-profiles-url
  "/api/pseudovision/api/ffmpeg/profiles")

(defn- channel-url [channel-id]
  (str "/api/pseudovision/api/channels/" channel-id))

(rf/reg-event-fx
 ::load-ffmpeg-profiles
 (fn [{:keys [db]} _]
   ;; Cached for the session; once loaded (even to false on a missing endpoint)
   ;; we don't refetch, so a channel without the feature doesn't hammer the BFF.
   (if (contains? db :ffmpeg-profiles)
     {:db db}
     {:db          db
      ::fetch-json {:url         ffmpeg-profiles-url
                    :keywordize? true
                    :on-success  [::load-ffmpeg-profiles-success]
                    :on-failure  [::load-ffmpeg-profiles-failure]}})))

(rf/reg-event-db
 ::load-ffmpeg-profiles-success
 (fn [db [_ body]]
   (assoc db :ffmpeg-profiles (vec (if (map? body) (:items body) body)))))

(rf/reg-event-db
 ::load-ffmpeg-profiles-failure
 (fn [db [_ error]]
   ;; Optional feature: a missing/renamed endpoint just hides the selector.
   (js/console.debug "Could not load ffmpeg profiles:" error)
   (assoc db :ffmpeg-profiles false)))

(rf/reg-event-fx
 ::set-channel-ffmpeg-profile
 (fn [{:keys [db]} [_ channel-id profile-id]]
   (let [k [:set-ffmpeg-profile channel-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (channel-url channel-id)
                     :method     "PATCH"
                     :body       {:ffmpeg-profile-id profile-id}
                     :on-success [::set-channel-ffmpeg-profile-success channel-id]
                     :on-failure [::set-channel-ffmpeg-profile-failure channel-id]}})))

(rf/reg-event-fx
 ::set-channel-ffmpeg-profile-success
 (fn [_ [_ channel-id _status]]
   ;; Reload channels so the selector reflects the persisted profile.
   (update (action-success-fx [:set-ffmpeg-profile channel-id] "Profile updated")
           :dispatch-n (fnil conj []) [::load-channels])))

(rf/reg-event-fx
 ::set-channel-ffmpeg-profile-failure
 (fn [_ [_ channel-id error]]
   ;; action-error-fx expects a response-shaped map; wrap the fetch error string.
   (action-error-fx [:set-ffmpeg-profile channel-id] {:body {:message error}})))

;; ---------------------------------------------------------------------------
;; Channel strategic guidance
;;
;; Free-text scheduling guidance the operator sets per channel; Tunarr Scheduler
;; feeds it to whatever builds the channel's playout. Keyed in the scheduler by
;; the channel's slug — its lower-cased name (e.g. "Spectrum" → "spectrum") —
;; which is also the {channel} path segment:
;;   GET /api/scheduling/channels/{channel}/guidance → {:strategic_guidance "..."}
;;   PUT /api/scheduling/channels/{channel}/guidance   {:strategic_guidance "..."}
;; Reads/writes go straight to the BFF (not martian): the endpoint isn't in the
;; generated client, and a plain fetch keeps the JSON key exactly as the
;; scheduler expects (snake_case, not martian's kebab-case coercion).
;;
;; Cache shape under [:channel-guidance slug]:
;;   absent                → never requested
;;   :loading              → GET in flight
;;   false                 → load failed
;;   {:guidance <str|nil>} → loaded (:guidance nil when none is set yet)
;; ---------------------------------------------------------------------------

(defn- channel-guidance-url [channel-slug]
  (str "/api/tunarr-scheduler/api/scheduling/channels/"
       (js/encodeURIComponent channel-slug) "/guidance"))

(defn- guidance-from-body [body]
  ;; ::fetch-json keywordizes the raw JSON verbatim, so the snake_case key
  ;; survives as :strategic_guidance; tolerate a couple of plausible shapes.
  (or (:strategic_guidance body)
      (:strategic-guidance body)
      (get-in body [:guidance :strategic_guidance])))

(rf/reg-event-fx
 ::load-channel-guidance
 (fn [{:keys [db]} [_ channel-slug]]
   ;; Once requested (loading / loaded / failed) we don't refetch, so the view
   ;; can safely trigger the load on render without a dispatch storm.
   (if (or (nil? channel-slug) (contains? (:channel-guidance db) channel-slug))
     {:db db}
     {:db          (assoc-in db [:channel-guidance channel-slug] :loading)
      ::fetch-json {:url         (channel-guidance-url channel-slug)
                    :keywordize? true
                    :on-success  [::load-channel-guidance-success channel-slug]
                    :on-failure  [::load-channel-guidance-failure channel-slug]}})))

(rf/reg-event-db
 ::load-channel-guidance-success
 (fn [db [_ channel-slug body]]
   (assoc-in db [:channel-guidance channel-slug] {:guidance (guidance-from-body body)})))

(rf/reg-event-db
 ::load-channel-guidance-failure
 (fn [db [_ channel-slug error]]
   ;; A missing guidance endpoint / no stored guidance just leaves the editor
   ;; empty, so keep this quiet rather than error-logging.
   (js/console.debug "Could not load guidance for channel" channel-slug ":" error)
   (assoc-in db [:channel-guidance channel-slug] false)))

(rf/reg-event-fx
 ::set-channel-guidance
 (fn [{:keys [db]} [_ channel-slug guidance]]
   (let [k [:channel-guidance channel-slug]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (channel-guidance-url channel-slug)
                     :method     "PUT"
                     :body       {:strategic_guidance guidance}
                     :on-success [::store-channel-guidance channel-slug k
                                  (if (seq guidance) "Guidance saved" "Guidance cleared")
                                  guidance]
                     :on-failure [::change-channel-guidance-failure k]}})))

(rf/reg-event-fx
 ::store-channel-guidance
 (fn [{:keys [db]} [_ channel-slug k message guidance _response]]
   ;; The value we sent is authoritative; store it directly so the view updates
   ;; without a follow-up GET (the PUT response shape isn't relied upon).
   (assoc (action-success-fx k message)
          :db (assoc-in db [:channel-guidance channel-slug] {:guidance (not-empty guidance)}))))

(rf/reg-event-fx
 ::change-channel-guidance-failure
 (fn [_ [_ k error]]
   ;; ::http-mutate hands us an error string; wrap it in the response shape.
   (action-error-fx k {:body {:message error}})))

;; ---------------------------------------------------------------------------
;; Channel scheduling regeneration (quarterly → monthly → weekly → playout)
;;
;; Tunarr Scheduler's periodic tasks are normally triggered by k8s CronJobs
;; (see its deploy/k8s), but operators need to re-run any stage on demand —
;; e.g. after editing guidance, or to recover from a bad LLM proposal. All
;; three POST endpoints accept an optional repeatable ?channel=<config-key>
;; selector to scope the run to one channel (omitting it runs every
;; configured channel); we always pass the current channel's slug, the same
;; lower-cased-name identifier already used for strategic guidance.
;;
;; Quarterly and monthly are heavy LLM-backed jobs and return 202 + a job id,
;; tracked like any other job on the Jobs page (source :tunarr-scheduler,
;; type :media/scheduling-quarterly / :media/scheduling-monthly). Weekly is a
;; fast, synchronous grid-expansion step (no LLM call) and returns 200 once
;; done. As with guidance, these go straight to the BFF rather than through
;; martian: the endpoints take no body, so there's nothing for martian's
;; query-schema coercion to drop, but a plain fetch keeps this consistent
;; with the rest of the scheduling API.
;; ---------------------------------------------------------------------------

(defn- scheduling-task-url [task channel-slug]
  (str "/api/tunarr-scheduler/api/scheduling/" (name task)
       "?channel=" (js/encodeURIComponent channel-slug)))

(rf/reg-event-fx
 ::trigger-regenerate-quarterly
 (fn [{:keys [db]} [_ channel-slug]]
   (let [k [:regenerate-quarterly channel-slug]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (scheduling-task-url :quarterly channel-slug)
                     :method     "POST"
                     :on-success [::regenerate-scheduling-success k "Quarterly regeneration started"]
                     :on-failure [::regenerate-scheduling-failure k]}})))

(rf/reg-event-fx
 ::trigger-regenerate-monthly
 (fn [{:keys [db]} [_ channel-slug]]
   (let [k [:regenerate-monthly channel-slug]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (scheduling-task-url :monthly channel-slug)
                     :method     "POST"
                     :on-success [::regenerate-scheduling-success k "Monthly regeneration started"]
                     :on-failure [::regenerate-scheduling-failure k]}})))

(rf/reg-event-fx
 ::trigger-regenerate-weekly
 (fn [{:keys [db]} [_ channel-slug]]
   (let [k [:regenerate-weekly channel-slug]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (scheduling-task-url :weekly channel-slug)
                     :method     "POST"
                     :on-success [::regenerate-scheduling-success k "Weekly schedule regenerated"]
                     :on-failure [::regenerate-scheduling-failure k]}})))

(rf/reg-event-fx
 ::regenerate-scheduling-success
 (fn [_ [_ action-key message _response]]
   (action-success-fx action-key message)))

(rf/reg-event-fx
 ::regenerate-scheduling-failure
 (fn [_ [_ action-key error]]
   ;; ::http-mutate hands us an error string; wrap it in the response shape.
   (action-error-fx action-key {:body {:message error}})))

;; ---------------------------------------------------------------------------
;; Quarterly grid ("outline") — read-only view of the frozen daypart skeleton
;; and rotation strips Tunabrain generated for a channel's current quarter,
;; plus the feasibility snapshot it was frozen against. Defaults server-side
;; to the current quarter/year.
;;
;; Cache shape under [:channel-grid slug]:
;;   absent  → never requested
;;   :loading → GET in flight
;;   false   → no frozen grid yet, or the load failed (the endpoint 404s until
;;             the first quarterly run freezes one — same quiet-failure
;;             handling as channel guidance, since the common case is simply
;;             "nothing generated yet")
;;   {...}   → the GridRecord body: {:channel :quarter :year :version :status
;;             :grid {:skeleton {:blocks [...]} :strips [...]} :feasibility}
;; ---------------------------------------------------------------------------

(defn- channel-grid-url [channel-slug]
  (str "/api/tunarr-scheduler/api/scheduling/channels/"
       (js/encodeURIComponent channel-slug) "/grid"))

(defn- fetch-channel-grid [db channel-slug]
  {:db          (assoc-in db [:channel-grid channel-slug] :loading)
   ::fetch-json {:url         (channel-grid-url channel-slug)
                 :keywordize? true
                 :on-success  [::load-channel-grid-success channel-slug]
                 :on-failure  [::load-channel-grid-failure channel-slug]}})

(rf/reg-event-fx
 ::load-channel-grid
 (fn [{:keys [db]} [_ channel-slug]]
   ;; Loaded lazily once per slug, like guidance; ::reload-channel-grid bypasses
   ;; this cache guard for an explicit manual refresh.
   (if (or (nil? channel-slug) (contains? (:channel-grid db) channel-slug))
     {:db db}
     (fetch-channel-grid db channel-slug))))

(rf/reg-event-fx
 ::reload-channel-grid
 (fn [{:keys [db]} [_ channel-slug]]
   (when channel-slug
     (fetch-channel-grid db channel-slug))))

(rf/reg-event-db
 ::load-channel-grid-success
 (fn [db [_ channel-slug body]]
   (assoc-in db [:channel-grid channel-slug] body)))

(rf/reg-event-db
 ::load-channel-grid-failure
 (fn [db [_ channel-slug error]]
   (js/console.debug "Could not load quarterly grid for channel" channel-slug ":" error)
   (assoc-in db [:channel-grid channel-slug] false)))

;; ---------------------------------------------------------------------------
;; Media tag management
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::load-media-tags
 (fn [{:keys [db]} [_ numeric-id]]
   {:db db
    :dispatch [::martian/request
               :get-api-media-items-id-tags
               {::martian/instance-id :pseudovision
                :id numeric-id}
               [::load-media-tags-success numeric-id]
               [::load-media-tags-failure numeric-id]]}))

(rf/reg-event-db
 ::load-media-tags-success
 (fn [db [_ numeric-id response]]
   (assoc-in db [:media-tags numeric-id] (vec (:body response)))))

(rf/reg-event-db
 ::load-media-tags-failure
 (fn [db [_ numeric-id response]]
   (log-request-failure (str "Failed to load tags for media: " numeric-id) response)
   (assoc-in db [:media-tags numeric-id] [])))

;; Tunarr Scheduler — not Pseudovision — is the source of truth for tags: it
;; takes Pseudovision's tags, prunes them, regenerates via Tunabrain, and syncs
;; the result back to Pseudovision. So manual edits are applied in Tunarr
;; Scheduler; editing tags in Pseudovision instead would get clobbered on the
;; next sync. `:media-id` in the path is the item's Jellyfin remote-key (the
;; endpoint resolves external ids through Pseudovision). Every mutation returns
;; the item's current tags ({:media-id :tags}), so we store that directly rather
;; than refetching. Writes go straight to the BFF via ::http-mutate.
;;   GET/POST/PUT /api/media-item/{media-id}/tags   {:tags [...]}
;;   DELETE       /api/media-item/{media-id}/tags/{tag}
;; `media-id` here is the app-db key (route id) the tags are cached under.

(defn- media-item-tags-url [remote-key]
  (str "/api/tunarr-scheduler/api/media-item/" remote-key "/tags"))

(rf/reg-event-fx
 ::add-media-tag
 (fn [{:keys [db]} [_ media-id remote-key tag]]
   (let [k [:add-tag media-id tag]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (media-item-tags-url remote-key)
                     :method     "POST"
                     :body       {:tags [tag]}
                     :on-success [::store-media-tags media-id k (str "Added tag: " tag)]
                     :on-failure [::change-media-tag-failure k]}})))

(rf/reg-event-fx
 ::remove-media-tag
 (fn [{:keys [db]} [_ media-id remote-key tag]]
   (let [k [:remove-tag media-id tag]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-tags-url remote-key) "/" (js/encodeURIComponent tag))
                     :method     "DELETE"
                     :on-success [::store-media-tags media-id k (str "Removed tag: " tag)]
                     :on-failure [::change-media-tag-failure k]}})))

(rf/reg-event-fx
 ::store-media-tags
 (fn [{:keys [db]} [_ media-id k message body]]
   ;; body is the MediaTagsResponse {:media-id :tags} returned by the mutation.
   (assoc (action-success-fx k message)
          :db (assoc-in db [:media-item-tags media-id] (vec (:tags body))))))

(rf/reg-event-fx
 ::change-media-tag-failure
 (fn [_ [_ k error]]
   ;; ::http-mutate hands us an error string; wrap it in the response shape.
   (action-error-fx k {:body {:message error}})))

;; ---------------------------------------------------------------------------
;; Media category (dimension) management
;;
;; Unlike tags (which are read from Pseudovision), a media item's dimension
;; values — audience, channel, etc. — are the categories stored in Tunarr
;; Scheduler, edited there for the same source-of-truth reason as tags. Reads
;; stay on the existing endpoint; per-dimension edits use the media-item routes
;; (the category name is a path segment, and each mutation returns that one
;; dimension's current values as {:media-id :category :values}):
;;   POST   /api/media-item/{media-id}/categories/{category}   {:values [...] :rationale ...}
;;   DELETE /api/media-item/{media-id}/categories/{category}/values/{value}
;;
;; Writes go straight to the BFF via ::http-mutate; the response's value list is
;; merged into the cached categories map. `remote-key` is the path id; `media-id`
;; is the app-db key the categories are cached under.
;; ---------------------------------------------------------------------------

(defn- media-item-category-url [remote-key category]
  (str "/api/tunarr-scheduler/api/media-item/" remote-key
       "/categories/" (js/encodeURIComponent category)))

(rf/reg-event-fx
 ::add-media-category
 (fn [{:keys [db]} [_ media-id remote-key dimension value]]
   (let [k [:add-category media-id dimension value]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (media-item-category-url remote-key dimension)
                     :method     "POST"
                     :body       {:values [value] :rationale "manual edit from Marquee"}
                     :on-success [::store-media-category media-id k (str "Added " dimension ": " value)]
                     :on-failure [::change-media-category-failure k]}})))

(rf/reg-event-fx
 ::remove-media-category
 (fn [{:keys [db]} [_ media-id remote-key dimension value]]
   (let [k [:remove-category media-id dimension value]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-category-url remote-key dimension)
                                       "/values/" (js/encodeURIComponent value))
                     :method     "DELETE"
                     :on-success [::store-media-category media-id k (str "Removed " dimension ": " value)]
                     :on-failure [::change-media-category-failure k]}})))

(rf/reg-event-fx
 ::store-media-category
 (fn [{:keys [db]} [_ media-id k message body]]
   ;; body is MediaCategoryValuesResponse {:media-id :category :values} for the
   ;; one edited dimension. Merge it into the cached {dimension → [value …]} map,
   ;; normalising the key to a string (the map can carry either string or keyword
   ;; keys depending on which loader populated it) and dropping the dimension
   ;; entirely once its last value is removed.
   (let [dim-name (key-name (:category body))
         values   (vec (:values body))
         existing (get-in db [:media-categories media-id])
         base     (if (map? existing) existing {})
         cleaned  (into {} (remove (fn [[ck _]] (= (key-name ck) dim-name)) base))
         cats     (if (seq values) (assoc cleaned dim-name values) cleaned)]
     (assoc (action-success-fx k message)
            :db (assoc-in db [:media-categories media-id] cats)))))

(rf/reg-event-fx
 ::change-media-category-failure
 (fn [_ [_ k error]]
   ;; ::http-mutate hands us an error string; wrap it in the response shape
   ;; action-error-fx expects.
   (action-error-fx k {:body {:message error}})))

;; ---------------------------------------------------------------------------
;; Media grounding context
;;
;; Tunabrain grounds its tag/category answers on a per-item "context" (a resolved
;; reference summary, its provenance, and reference links). It's captured
;; automatically after each run (usually a Wikipedia auto-search), but that can
;; land on the wrong article — so operators can view and correct it here. Edits
;; are sticky: once touched they're re-sent to Tunabrain and not overwritten by
;; an automatic re-tag.
;;
;; Like tags/categories, context lives in Tunarr Scheduler keyed by the item's
;; Jellyfin remote-key (the endpoint resolves external ids), so we go straight to
;; the BFF. Every mutation returns the full context envelope {:media-id :context}
;; (context is nil when none is stored), which we cache directly rather than
;; refetching. Grounding precedence on the next run is summary → text → links,
;; else a fresh Wikipedia auto-search.
;;   GET/PUT/DELETE /api/media-item/{media-id}/context
;;   POST/DELETE    /api/media-item/{media-id}/context/links     {:link ...}
;;   PUT/DELETE     /api/media-item/{media-id}/context/text      {:text ...}
;;   PUT/DELETE     /api/media-item/{media-id}/context/summary   {:summary ...}
;; `media-id` here is the app-db key the context is cached under; `remote-key` is
;; the path id sent to the scheduler.

(defn- media-item-context-url [remote-key]
  (str "/api/tunarr-scheduler/api/media-item/" remote-key "/context"))

;; Cache shape under [:media-context media-id]:
;;   nil            → not loaded yet (loading)
;;   false          → failed to load
;;   {:context m}   → loaded; m is the context map, or nil when none is stored.
;; Wrapping in a map lets "loaded, no context" (a real state — grounded by
;; auto-search) stay distinct from "still loading".

(rf/reg-event-fx
 ::load-media-context
 (fn [{:keys [db]} [_ media-id remote-key]]
   {:db          db
    ::fetch-json {:url         (media-item-context-url remote-key)
                  :keywordize? true
                  :on-success  [::load-media-context-success media-id]
                  :on-failure  [::load-media-context-failure media-id]}}))

(rf/reg-event-db
 ::load-media-context-success
 (fn [db [_ media-id envelope]]
   (assoc-in db [:media-context media-id] {:context (:context envelope)})))

(rf/reg-event-db
 ::load-media-context-failure
 (fn [db [_ media-id error]]
   (js/console.debug "Could not load media context for" media-id ":" error)
   (assoc-in db [:media-context media-id] false)))

;; Shared success handler for every context mutation: the response is the full
;; envelope, so we replace the cached context wholesale (and flag the action
;; success + refresh jobs, like the other edit handlers).
(rf/reg-event-fx
 ::store-media-context
 (fn [{:keys [db]} [_ media-id k message envelope]]
   (assoc (action-success-fx k message)
          :db (assoc-in db [:media-context media-id] {:context (:context envelope)}))))

(rf/reg-event-fx
 ::change-media-context-failure
 (fn [_ [_ k error]]
   ;; ::http-mutate hands us an error string; wrap it in the response shape.
   (action-error-fx k {:body {:message error}})))

(rf/reg-event-fx
 ::set-media-context-summary
 (fn [{:keys [db]} [_ media-id remote-key summary]]
   (let [k [:context-summary media-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-context-url remote-key) "/summary")
                     :method     "PUT"
                     :body       {:summary summary}
                     :on-success [::store-media-context media-id k "Summary saved"]
                     :on-failure [::change-media-context-failure k]}})))

(rf/reg-event-fx
 ::clear-media-context-summary
 (fn [{:keys [db]} [_ media-id remote-key]]
   (let [k [:context-summary media-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-context-url remote-key) "/summary")
                     :method     "DELETE"
                     :on-success [::store-media-context media-id k "Summary cleared"]
                     :on-failure [::change-media-context-failure k]}})))

(rf/reg-event-fx
 ::set-media-context-text
 (fn [{:keys [db]} [_ media-id remote-key text]]
   (let [k [:context-text media-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-context-url remote-key) "/text")
                     :method     "PUT"
                     :body       {:text text}
                     :on-success [::store-media-context media-id k "Note saved"]
                     :on-failure [::change-media-context-failure k]}})))

(rf/reg-event-fx
 ::clear-media-context-text
 (fn [{:keys [db]} [_ media-id remote-key]]
   (let [k [:context-text media-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-context-url remote-key) "/text")
                     :method     "DELETE"
                     :on-success [::store-media-context media-id k "Note cleared"]
                     :on-failure [::change-media-context-failure k]}})))

(rf/reg-event-fx
 ::add-media-context-link
 (fn [{:keys [db]} [_ media-id remote-key link]]
   (let [k [:context-link-add media-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-context-url remote-key) "/links")
                     :method     "POST"
                     :body       {:link link}
                     :on-success [::store-media-context media-id k "Link added"]
                     :on-failure [::change-media-context-failure k]}})))

(rf/reg-event-fx
 ::remove-media-context-link
 (fn [{:keys [db]} [_ media-id remote-key link]]
   ;; NB: this DELETE carries a JSON body — ::http-mutate sends one whenever
   ;; :body is present, so the scheduler knows which link to drop.
   (let [k [:context-link-remove media-id link]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (str (media-item-context-url remote-key) "/links")
                     :method     "DELETE"
                     :body       {:link link}
                     :on-success [::store-media-context media-id k "Link removed"]
                     :on-failure [::change-media-context-failure k]}})))

(rf/reg-event-fx
 ::reset-media-context
 (fn [{:keys [db]} [_ media-id remote-key]]
   ;; DELETE the whole context: forget operator edits and let the next run fall
   ;; back to (and re-capture) a fresh Wikipedia auto-search.
   (let [k [:context-reset media-id]]
     {:db           (assoc-in db [:action-states k] {:status :loading})
      ::http-mutate {:url        (media-item-context-url remote-key)
                     :method     "DELETE"
                     :on-success [::store-media-context media-id k "Reset to auto-search"]
                     :on-failure [::change-media-context-failure k]}})))

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
 (fn [{:keys [db]} [_ library-id _response]]
   (let [lib-name (some #(when (= (:id %) library-id) (:name %)) (:media-libraries db))]
     (update (action-success-fx [:scan-library library-id] "Scan triggered")
             :dispatch-n (fnil conj []) [::poll-library-job library-id lib-name]))))

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
   (update (action-success-fx :sync-channels "Channels synced")
           :dispatch-n (fnil conj []) [::poll-channels-after-sync])))

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
 (fn [{:keys [db]} [_ action library-name _response]]
   (update (action-success-fx [action library-name] (library-action-label action))
           :dispatch-n (fnil conj []) [::poll-library-job (:selected-library-id db) library-name])))

(rf/reg-event-fx
 ::trigger-library-action-failure
 (fn [_ [_ action library-name response]]
   (action-error-fx [action library-name] response)))

;; ---------------------------------------------------------------------------
;; Process timestamp reset (library-wide and per-item)
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::trigger-reset-library-process
 (fn [{:keys [db]} [_ library-name process]]
   (let [k [:reset-process library-name process]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :delete-api-media-library-process-process-reset
                 {::martian/instance-id :tunarr-scheduler
                  :library library-name
                  :process process}
                 [::trigger-reset-library-process-success library-name process]
                 [::trigger-reset-library-process-failure library-name process]]})))

(rf/reg-event-fx
 ::trigger-reset-library-process-success
 (fn [_ [_ library-name process _response]]
   (action-success-fx [:reset-process library-name process]
                      (str "Reset " process " for " library-name))))

(rf/reg-event-fx
 ::trigger-reset-library-process-failure
 (fn [_ [_ library-name process response]]
   (action-error-fx [:reset-process library-name process] response)))

(rf/reg-event-fx
 ::trigger-reset-media-item-process
 (fn [{:keys [db]} [_ media-id process]]
   (let [k [:reset-process media-id process]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :delete-api-media-item-media-id-process-process-reset
                 {::martian/instance-id :tunarr-scheduler
                  :media-id (str media-id)
                  :process process}
                 [::trigger-reset-media-item-process-success media-id process]
                 [::trigger-reset-media-item-process-failure media-id process]]})))

(rf/reg-event-fx
 ::trigger-reset-media-item-process-success
 (fn [_ [_ media-id process _response]]
   (action-success-fx [:reset-process media-id process]
                      (str "Reset " process))))

(rf/reg-event-fx
 ::trigger-reset-media-item-process-failure
 (fn [_ [_ media-id process response]]
   (action-error-fx [:reset-process media-id process] response)))

;; ---------------------------------------------------------------------------
;; Per-item curation actions
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 ::trigger-media-item-retag
 (fn [{:keys [db]} [_ media-id]]
   (let [k [:retag media-id]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :post-api-media-item-media-id-retag
                 {::martian/instance-id :tunarr-scheduler
                  :media-id (str media-id)}
                 [::trigger-media-item-retag-success media-id]
                 [::trigger-media-item-retag-failure media-id]]})))

(rf/reg-event-fx
 ::trigger-media-item-retag-success
 (fn [_ [_ media-id _response]]
   (action-success-fx [:retag media-id] "Retag submitted")))

(rf/reg-event-fx
 ::trigger-media-item-retag-failure
 (fn [_ [_ media-id response]]
   (action-error-fx [:retag media-id] response)))

(rf/reg-event-fx
 ::trigger-media-item-recategorize
 (fn [{:keys [db]} [_ media-id]]
   (let [k [:recategorize media-id]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :post-api-media-item-media-id-recategorize
                 {::martian/instance-id :tunarr-scheduler
                  :media-id (str media-id)}
                 [::trigger-media-item-recategorize-success media-id]
                 [::trigger-media-item-recategorize-failure media-id]]})))

(rf/reg-event-fx
 ::trigger-media-item-recategorize-success
 (fn [_ [_ media-id _response]]
   (action-success-fx [:recategorize media-id] "Recategorize submitted")))

(rf/reg-event-fx
 ::trigger-media-item-recategorize-failure
 (fn [_ [_ media-id response]]
   (action-error-fx [:recategorize media-id] response)))

(rf/reg-event-fx
 ::trigger-media-item-sync-pseudovision
 (fn [{:keys [db]} [_ media-id]]
   (let [k [:sync-pseudovision media-id]]
     {:db       (assoc-in db [:action-states k] {:status :loading})
      :dispatch [::martian/request
                 :post-api-media-item-media-id-sync-pseudovision
                 {::martian/instance-id :tunarr-scheduler
                  :media-id (str media-id)}
                 [::trigger-media-item-sync-pseudovision-success media-id]
                 [::trigger-media-item-sync-pseudovision-failure media-id]]})))

(rf/reg-event-fx
 ::trigger-media-item-sync-pseudovision-success
 (fn [{:keys [db]} [_ media-id _response]]
   (update (action-success-fx [:sync-pseudovision media-id] "Tags synced to Pseudovision")
           :dispatch-n (fnil conj [])
           [::load-media-item media-id])))

(rf/reg-event-fx
 ::trigger-media-item-sync-pseudovision-failure
 (fn [_ [_ media-id response]]
   (action-error-fx [:sync-pseudovision media-id] response)))

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
 ::set-collection-filter
 (fn [db [_ text]]
   (assoc db :collection-filter text)))

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
                        (assoc :current-collection-id collection-id)
                        (assoc :collection-filter ""))
      :push-history (routes/collection-path collection-id)
      :dispatch-n   dispatches})))

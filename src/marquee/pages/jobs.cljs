(ns marquee.pages.jobs
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.card :refer [card card-content]]))

(defn- format-ts [s]
  (when s
    (try
      (.toLocaleString (js/Date. s))
      (catch :default _ s))))

(defn- format-duration [ms]
  (when (and ms (>= ms 0))
    (let [total-s (quot ms 1000)
          h (quot total-s 3600)
          m (quot (rem total-s 3600) 60)
          s (rem total-s 60)]
      (cond
        (pos? h) (str h "h " m "m")
        (pos? m) (str m "m " s "s")
        :else    (str s "s")))))

(defn- status-badge [status]
  (let [[label cls] (case (keyword status)
                      :running   ["Running"   "bg-blue-100 text-blue-800 border-blue-300"]
                      :queued    ["Queued"    "bg-yellow-100 text-yellow-800 border-yellow-300"]
                      :pending   ["Pending"   "bg-yellow-100 text-yellow-800 border-yellow-300"]
                      :succeeded ["Succeeded" "bg-green-100 text-green-800 border-green-300"]
                      :complete  ["Complete"  "bg-green-100 text-green-800 border-green-300"]
                      :failed    ["Failed"    "bg-red-100 text-red-800 border-red-300"]
                      :error     ["Error"     "bg-red-100 text-red-800 border-red-300"]
                      [status    "bg-muted text-muted-foreground border-border"])]
    [:span {:class (str "text-xs font-medium px-2 py-0.5 rounded border " cls)}
     label]))

(defn- progress-section
  "Rich progress for item-based jobs: bar, done/failed/skipped counts,
   current item, and phase."
  [{:keys [phase total completed failed skipped current-item]} running?]
  (let [done (+ (or completed 0) (or failed 0) (or skipped 0))
        pct  (when (and total (pos? total))
               (min 100 (js/Math.round (* 100 (/ done total)))))]
    [:div {:class "mt-2 space-y-1"}
     (when pct
       [:div {:class "h-1.5 w-full max-w-md rounded bg-muted overflow-hidden"}
        [:div {:class "h-full rounded bg-blue-500 transition-all"
               :style {:width (str pct "%")}}]])
     [:div {:class "text-xs text-muted-foreground flex gap-3 flex-wrap"}
      (when phase
        [:span {:class "font-medium"} phase])
      (when total
        [:span (str done " / " total " done" (when pct (str " (" pct "%)")))])
      (when (and failed (pos? failed))
        [:span {:class "text-red-600"} (str failed " failed")])
      (when (and skipped (pos? skipped))
        [:span (str skipped " skipped")])]
     (when (and running? current-item)
       (let [{:keys [name id]} current-item]
         [:div {:class "text-xs text-muted-foreground truncate max-w-md"}
          "Current: "
          [:span {:class "text-foreground"} (or name id "—")]]))]))

(defn- tag-audit-result
  "Report from a :media/tag-audit job: summary counts plus the list of tags
   recommended for removal with the LLM's reasons."
  [{:keys [tags-audited tags-removed removed dry-run]}]
  [:div {:class "mt-2 space-y-1"}
   [:div {:class "text-xs text-muted-foreground"}
    (str tags-audited " tags audited, "
         (count removed) " recommended for removal"
         (if dry-run " (dry run — nothing deleted)" (str ", " tags-removed " deleted")))]
   (when (seq removed)
     [:div {:class "max-h-64 overflow-y-auto rounded border bg-muted/30 divide-y"}
      (for [{:keys [tag reason]} removed]
        ^{:key tag}
        [:div {:class "px-2 py-1 text-xs flex gap-2"}
         [:span {:class "font-mono font-medium shrink-0"} tag]
         [:span {:class "text-muted-foreground"} reason]])])])

(defn- tag-triage-result
  "Report from a :media/tag-triage job: summary counts plus every decision
   other than keep (drop/merge/rename), with the LLM's rationale."
  [{:keys [tags-triaged kept deleted renamed skipped decisions dry-run]}]
  (let [changes (remove #(= "keep" (some-> (:action %) name)) decisions)]
    [:div {:class "mt-2 space-y-1"}
     [:div {:class "text-xs text-muted-foreground"}
      (str tags-triaged " tags triaged: " kept " kept, " deleted " dropped, "
           renamed " renamed"
           (when (and skipped (pos? skipped)) (str ", " skipped " skipped"))
           (when dry-run " (dry run — no changes applied)"))]
     (when (seq changes)
       [:div {:class "max-h-64 overflow-y-auto rounded border bg-muted/30 divide-y"}
        (for [{:keys [tag action replacement rationale]} changes]
          ^{:key tag}
          [:div {:class "px-2 py-1 text-xs flex gap-2 flex-wrap"}
           [:span {:class "font-mono font-medium shrink-0"}
            (str tag
                 (when replacement (str " → " replacement)))]
           [:span {:class "uppercase text-[10px] font-semibold text-muted-foreground border rounded px-1 shrink-0"}
            (some-> action name)]
           (when rationale
             [:span {:class "text-muted-foreground"} rationale])])])]))

(defn- generic-result
  "Fallback result rendering: scalar entries inline; collections summarized
   by size rather than dumped raw."
  [result]
  [:div {:class "mt-2 text-xs text-muted-foreground"}
   (for [[k v] result]
     ^{:key k}
     [:span {:class "mr-4"}
      (str (name k) ": " (if (coll? v) (str (count v) " items") v))])])

(defn- result-section [type result]
  (case (keyword type)
    :media/tag-audit  [tag-audit-result result]
    :media/tag-triage [tag-triage-result result]
    [generic-result result]))

(defn- source-badge [source]
  (when source
    [:span {:class "text-xs text-muted-foreground border rounded px-1"}
     (case source
       :pseudovision     "Pseudovision"
       :tunarr-scheduler  "Tunarr Scheduler"
       (name source))]))

(defn- job-row [{:keys [type status library metadata created-at completed-at
                        error result progress duration-ms source]}]
  (let [library (or library (:library metadata))
        dry-run? (or (:dry-run metadata) (:dry-run result))]
    [:div {:class "py-4 border-b last:border-0"}
     [:div {:class "flex items-start justify-between gap-4 flex-wrap"}
      [:div {:class "flex items-center gap-3 flex-wrap"}
       [status-badge status]
       [:span {:class "font-medium text-sm"} (or (some-> type name) (str type))]
       [source-badge source]
       (when library
         [:span {:class "text-xs text-muted-foreground border rounded px-1"} library])
       (when dry-run?
         [:span {:class "text-xs text-muted-foreground border rounded px-1"} "dry run"])]
      [:div {:class "text-xs text-muted-foreground flex gap-4"}
       (when created-at
         [:span (str "Started " (format-ts created-at))])
       (when completed-at
         [:span (str "Finished " (format-ts completed-at))])
       (when-let [dur (format-duration duration-ms)]
         [:span (str "Runtime " dur)])]]
     (when (map? progress)
       [progress-section progress (= :running (keyword status))])
     (when error
       [:div {:class "mt-2 p-2 rounded bg-red-50 border border-red-200 text-sm text-red-700 font-mono whitespace-pre-wrap"}
        (if (map? error) (or (:message error) (str error)) error)])
     (when (and result (map? result) (seq result))
       [result-section type result])]))

(defn- tag-curation-panel
  "Launchers for the catalog-wide tag governance jobs. Dry-run is on by
   default so recommendations can be reviewed in the job result before
   anything is deleted."
  []
  (let [{:keys [dry-run target-limit]} @(rf/subscribe [::subs/tag-task-options])]
    [card {}
     [card-content {:class "pt-6 space-y-3"}
      [:div
       [:h2 {:class "text-sm font-semibold"} "Tag curation"]
       [:p {:class "text-xs text-muted-foreground"}
        "Ask the LLM which tags are useful for scheduling. Audit flags tags to remove; triage also merges and renames, using usage counts and example titles."]]
      [:div {:class "flex items-end gap-4 flex-wrap"}
       [:label {:class "flex items-center gap-2 text-sm h-9"}
        [:input {:type      "checkbox"
                 :checked   (boolean dry-run)
                 :on-change #(rf/dispatch [::events/set-tag-task-option :dry-run
                                           (.. % -target -checked)])}]
        "Dry run (report only, change nothing)"]
       [:label {:class "flex items-center gap-2 text-sm"}
        "Target tag count (triage)"
        [:input {:type        "number"
                 :min         1
                 :placeholder "auto"
                 :class       "h-9 w-24 rounded-md border border-input bg-background px-2 text-sm"
                 :value       (or target-limit "")
                 :on-change   #(rf/dispatch [::events/set-tag-task-option :target-limit
                                             (let [v (js/parseInt (.. % -target -value))]
                                               (when-not (js/isNaN v) v))])}]]
       [:div {:class "w-px h-5 bg-border"}]
       [action-btn {:action-key :tag-audit
                    :label      "Audit Tags"
                    :on-click   #(rf/dispatch [::events/trigger-tag-audit])}]
       [action-btn {:action-key :tag-triage
                    :label      "Triage Tags"
                    :on-click   #(rf/dispatch [::events/trigger-tag-triage])}]]]]))

(defn page []
  (let [jobs     @(rf/subscribe [::subs/jobs])
        loading? @(rf/subscribe [::subs/jobs-loading?])]
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center justify-between gap-4"}
      [:div
       [:h1 {:class "text-3xl font-bold tracking-tight"} "Jobs"]
       [:p {:class "text-muted-foreground"} "Background processes from Tunarr Scheduler and Pseudovision."]]
      [button {:size :sm :variant :outline
               :disabled loading?
               :on-click #(rf/dispatch [::events/load-jobs])}
       (if loading? "Refreshing…" "Refresh")]]

     [tag-curation-panel]

     (cond
       (nil? jobs)
       [:p {:class "text-muted-foreground"} "Loading…"]

       (empty? jobs)
       [:p {:class "text-muted-foreground"} "No jobs found."]

       :else
       (let [active?  #(contains? #{:running :pending :queued} (keyword (:status %)))
             running  (filter active? jobs)
             finished (remove active? jobs)]
         [:div {:class "space-y-6"}
          (when (seq running)
            [:div
             [:h2 {:class "text-sm font-semibold uppercase tracking-wide text-muted-foreground mb-3"}
              (str "Running (" (count running) ")")]
             [card {}
              [card-content {:class "p-0 divide-y"}
               (for [job running]
                 ^{:key (:id job)}
                 [job-row job])]]])
          (when (seq finished)
            [:div
             [:h2 {:class "text-sm font-semibold uppercase tracking-wide text-muted-foreground mb-3"}
              (str "Recent (" (count finished) ")")]
             [card {}
              [card-content {:class "p-0 divide-y"}
               (for [job finished]
                 ^{:key (:id job)}
                 [job-row job])]]])]))]))

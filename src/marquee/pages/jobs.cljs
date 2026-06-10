(ns marquee.pages.jobs
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-content]]))

(defn- format-ts [s]
  (when s
    (try
      (.toLocaleString (js/Date. s))
      (catch :default _ s))))

(defn- status-badge [status]
  (let [[label cls] (case (keyword status)
                      :running  ["Running"  "bg-blue-100 text-blue-800 border-blue-300"]
                      :pending  ["Pending"  "bg-yellow-100 text-yellow-800 border-yellow-300"]
                      :complete ["Complete" "bg-green-100 text-green-800 border-green-300"]
                      :error    ["Error"    "bg-red-100 text-red-800 border-red-300"]
                      [status   "bg-muted text-muted-foreground border-border"])]
    [:span {:class (str "text-xs font-medium px-2 py-0.5 rounded border " cls)}
     label]))

(defn- job-row [{:keys [id type status library created-at completed-at error result]}]
  [:div {:class "py-4 border-b last:border-0"}
   [:div {:class "flex items-start justify-between gap-4 flex-wrap"}
    [:div {:class "flex items-center gap-3 flex-wrap"}
     [status-badge status]
     [:span {:class "font-medium text-sm"} (or (some-> type name) (str type))]
     (when library
       [:span {:class "text-xs text-muted-foreground border rounded px-1"} library])]
    [:div {:class "text-xs text-muted-foreground flex gap-4"}
     (when created-at
       [:span (str "Started " (format-ts created-at))])
     (when completed-at
       [:span (str "Finished " (format-ts completed-at))])]]
   (when error
     [:div {:class "mt-2 p-2 rounded bg-red-50 border border-red-200 text-sm text-red-700 font-mono whitespace-pre-wrap"}
      error])
   (when (and result (map? result) (seq result))
     [:div {:class "mt-2 text-xs text-muted-foreground"}
      (for [[k v] result]
        ^{:key k}
        [:span {:class "mr-4"} (str (name k) ": " v)])])])

(defn page []
  (let [jobs     @(rf/subscribe [::subs/jobs])
        loading? @(rf/subscribe [::subs/jobs-loading?])]
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center justify-between gap-4"}
      [:div
       [:h1 {:class "text-3xl font-bold tracking-tight"} "Jobs"]
       [:p {:class "text-muted-foreground"} "Background processes from Tunarr Scheduler."]]
      [button {:size :sm :variant :outline
               :disabled loading?
               :on-click #(rf/dispatch [::events/load-jobs])}
       (if loading? "Refreshing…" "Refresh")]]

     (cond
       (nil? jobs)
       [:p {:class "text-muted-foreground"} "Loading…"]

       (empty? jobs)
       [:p {:class "text-muted-foreground"} "No jobs found."]

       :else
       (let [running  (filter #(#{:running :pending "running" "pending"} (:status %)) jobs)
             finished (remove #(#{:running :pending "running" "pending"} (:status %)) jobs)]
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

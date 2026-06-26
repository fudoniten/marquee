(ns marquee.pages.media
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content
                                             card-footer]]))

(defn- action-group
  "A labelled row of task launch buttons."
  [label & buttons]
  [:div {:class "flex items-center gap-2 flex-wrap"}
   [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground w-24 shrink-0"}
    label]
   (into [:<>] buttons)])

(defn- jellyfin-image-url [remote-key]
  (str "/api/jellyfin/Items/" remote-key "/Images/Primary?maxHeight=240&quality=85"))

(defn media-item-card [{:keys [id name title description kind duration remote-key]}]
  [card {:class "mb-4 overflow-hidden"}
   (when remote-key
     [:div {:class "w-full bg-muted flex items-center justify-center overflow-hidden" :style {:max-height "160px"}}
      [:img {:src     (jellyfin-image-url remote-key)
             :alt     (or title name "")
             :class   "w-full object-cover object-top"
             :style   {:max-height "160px"}
             :loading "lazy"
             :on-error #(-> % .-target .-parentElement .-style (aset "display" "none"))}]])
   [card-header {}
    [card-title {} (or title name (str "Item #" id))]
    (when kind
      [card-description {} (str "Type: " kind)])]
   [card-content {}
    (when description
      [:p {:class "text-sm text-muted-foreground mb-2"} description])
    (when duration
      [:p {:class "text-xs text-muted-foreground"}
       (str "Duration: " (js/Math.floor (/ duration 60)) " min")])]
   [card-footer {:class "flex gap-2"}
    [button {:size :sm
             :variant :outline
             :on-click #(rf/dispatch [::events/navigate-to-media-detail id])}
     "View Details"]]])

(defn library-selector [libraries selected-id]
  [:div {:class "flex items-center gap-4 mb-6"}
   [:label {:class "font-medium text-sm"} "Library:"]
   [:select {:class "flex h-10 rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
             :value (or selected-id "")
             :on-change #(rf/dispatch [::events/select-library (js/parseInt (.. % -target -value))])}
    [:option {:value "" :disabled true} "Select a library..."]
    (for [lib libraries]
      ^{:key (:id lib)}
      [:option {:value (:id lib)} (:name lib)])]])

(defn pagination-controls [current-page total-pages]
  (let [has-prev (> current-page 1)
        has-next (< current-page total-pages)]
    [:div {:class "flex items-center justify-between border-t pt-4 mt-6"}
     [:div {:class "flex items-center gap-2"}
      [button {:size :sm
               :variant :outline
               :disabled (not has-prev)
               :on-click #(rf/dispatch [::events/set-media-page (dec current-page)])}
       "← Previous"]
      [button {:size :sm
               :variant :outline
               :disabled (not has-next)
               :on-click #(rf/dispatch [::events/set-media-page (inc current-page)])}
       "Next →"]]
     [:div {:class "text-sm text-muted-foreground"}
      (str "Page " current-page " of " total-pages)]]))

(defn media-grid [items]
  (if (empty? items)
    [:p {:class "text-muted-foreground"} "No items found."]
    [:div {:class "grid gap-4"}
     (for [item items]
       ^{:key (:id item)}
       [media-item-card item])]))

(defn page []
  (let [libraries @(rf/subscribe [::subs/media-libraries])
        selected-library-id @(rf/subscribe [::subs/selected-library-id])
        all-items @(rf/subscribe [::subs/library-items selected-library-id])
        current-page @(rf/subscribe [::subs/media-current-page])
        paginated-items @(rf/subscribe [::subs/paginated-media-items])
        total-pages @(rf/subscribe [::subs/media-total-pages])
        selected-library @(rf/subscribe [::subs/selected-library])]
    [:div {:class "space-y-6"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "Media"]
      [:p {:class "text-muted-foreground"}
       "Browse media items from Pseudovision."]]

     ;; Loading state
     (when (nil? libraries)
       [:p {:class "text-muted-foreground"} "Loading libraries..."])

     ;; No libraries state
     (when (and (not (nil? libraries)) (empty? libraries))
       [:p {:class "text-muted-foreground"} "No libraries found."])

     ;; Library selector
     (when (seq libraries)
       [library-selector libraries selected-library-id])

     ;; Task launchers, grouped by what they touch. Catalog-wide tag curation
     ;; (audit/triage) lives on the Jobs page next to job tracking.
     (when selected-library-id
       (let [lib-name (:name selected-library)]
         [card {}
          [card-content {:class "pt-6 space-y-3"}
           [action-group "Scan & Sync"
            [action-btn {:action-key :sync-libraries
                         :label      "Sync Libraries"
                         :on-click   #(rf/dispatch [::events/trigger-sync-libraries])}]
            [action-btn {:action-key [:scan-library selected-library-id]
                         :label      "Scan"
                         :on-click   #(rf/dispatch [::events/trigger-scan-library selected-library-id])}]
            [action-btn {:action-key [:rescan lib-name]
                         :label      "Rescan"
                         :on-click   #(rf/dispatch [::events/trigger-library-action :rescan lib-name])}]
            [action-btn {:action-key [:sync-pseudovision-tags lib-name]
                         :label      "Sync Tags → Pseudovision"
                         :on-click   #(rf/dispatch [::events/trigger-library-action :sync-pseudovision-tags lib-name])}]]
            [action-group "AI Curation"
             [action-btn {:action-key [:retag-episodes lib-name]
                          :label      "Retag Episodes"
                          :on-click   #(rf/dispatch [::events/trigger-library-action :retag-episodes lib-name])}]
             [action-btn {:action-key [:add-taglines lib-name]
                          :label      "Add Taglines"
                          :on-click   #(rf/dispatch [::events/trigger-library-action :add-taglines lib-name])}]
             [action-btn {:action-key [:recategorize lib-name]
                          :label      "Recategorize"
                          :on-click   #(rf/dispatch [::events/trigger-library-action :recategorize lib-name])}]]
           [:p {:class "text-xs text-muted-foreground"}
            "Jobs run in the background — track progress on the "
            [:button {:class "underline"
                      :on-click #(rf/dispatch [::events/navigate :jobs])}
             "Jobs page"]
            ". Catalog-wide tag audit & triage launch from there too."]]]))

     ;; Selected library content
     (when selected-library-id
       [:div
        ;; Library info
        (when selected-library
          [:div {:class "mb-4"}
           [:h2 {:class "text-2xl font-semibold"} (:name selected-library)]
           (when all-items
             [:p {:class "text-sm text-muted-foreground"}
              (str (count all-items) " items total")])])

        ;; Loading items
        (cond
          (nil? all-items)
          [:p {:class "text-muted-foreground"} "Loading items..."]

          ;; Display items
          (seq all-items)
          [:div
           [media-grid paginated-items]
           (when (> total-pages 1)
             [pagination-controls current-page total-pages])]

          ;; No items
          :else
          [:p {:class "text-muted-foreground"} "No items in this library."])])]))


(ns marquee.pages.collections
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                              card-description card-content
                                              card-footer]]))

(defn- jellyfin-image-url [remote-key]
  (str "/api/jellyfin/Items/" remote-key "/Images/Primary?maxHeight=240&quality=85"))

(defn- create-form []
  (let [value (rf/subscribe [::subs/new-collection-name])]
    (fn []
      [:div {:class "flex items-center gap-2"}
       [:input {:class       "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                :placeholder "New collection name…"
                :value       (or @value "")
                :on-change   #(rf/dispatch [::events/set-new-collection-name (.. % -target -value)])
                :on-key-down #(when (= (.-key %) "Enter")
                                (rf/dispatch [::events/create-collection]))}]
       [button {:size     :sm
                :disabled (str/blank? @value)
                :on-click #(rf/dispatch [::events/create-collection])}
        "Create"]])))

(defn- collection-card [{:keys [id name items]}]
  [card {:class "cursor-pointer hover:border-primary/50 transition-colors"
         :on-click #(rf/dispatch [::events/navigate-to-collection id])}
   [card-header {}
    [card-title {:class "text-lg"} name]
    [card-description {} (str (count items) " item" (when (not= 1 (count items)) "s"))]]])

(defn- collection-item-card [{:keys [id name title description kind duration remote-key]} collection-id]
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
    [button {:size     :sm
             :variant  :outline
             :on-click #(rf/dispatch [::events/navigate-to-media-detail id])}
     "View Details"]
    [button {:size     :sm
             :variant  :ghost
             :class    "text-destructive hover:text-destructive"
             :on-click #(rf/dispatch [::events/remove-from-collection collection-id id])}
     "Remove"]]])

(defn- collection-detail [collection-id]
  (let [collection @(rf/subscribe [::subs/collection collection-id])
        items      @(rf/subscribe [::subs/collection-items collection-id])]
    [:div {:class "space-y-6"}
     [:div {:class "flex items-center gap-4"}
      [button {:variant  :ghost
               :size     :sm
               :on-click #(rf/dispatch [::events/navigate :collections])}
       "← Back to Collections"]
      [:div {:class "flex-1"}]
      [button {:size     :sm
               :variant  :destructive
               :on-click #(when (js/confirm (str "Delete \"" (:name collection) "\"?"))
                            (rf/dispatch [::events/delete-collection collection-id]))}
       "Delete Collection"]]

     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} (:name collection)]
      [:p {:class "text-muted-foreground"}
       (str (count (:items collection)) " item" (when (not= 1 (count (:items collection))) "s"))]]

     (cond
       (empty? (:items collection))
       [:p {:class "text-muted-foreground"} "This collection is empty. Add items from the media detail page."]

       (nil? items)
       [:p {:class "text-muted-foreground"} "Loading items…"]

       :else
       [:div {:class "grid gap-4"}
        (for [item items]
          ^{:key (:id item)}
          [collection-item-card item collection-id])])]))

(defn- collections-list []
  (let [collections @(rf/subscribe [::subs/collections])]
    [:div {:class "space-y-6"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "Collections"]
      [:p {:class "text-muted-foreground"}
       "Create and curate custom collections of media items."]]

     [create-form]

     (if (empty? collections)
       [:p {:class "text-muted-foreground"} "No collections yet. Create one above."]
       [:div {:class "grid gap-4"}
        (for [coll (sort-by :created-at > collections)]
          ^{:key (:id coll)}
          [collection-card coll])])]))

(defn page []
  (let [collection-id @(rf/subscribe [::subs/current-collection-id])]
    (if collection-id
      [collection-detail collection-id]
      [collections-list])))

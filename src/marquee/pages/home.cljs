(ns marquee.pages.home
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content
                                             card-footer]]))

(defn page []
  (let [counter @(rf/subscribe [::subs/counter])]
    [:div {:class "space-y-6"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "Home"]
      [:p {:class "text-muted-foreground"}
       "A re-frame + Tailwind + shadcn/ui starter, compiled with shadow-cljs."]]
     [card {}
      [card-header {}
       [card-title {} "Interactive counter"]
       [card-description {} "State lives in the re-frame app-db."]]
      [card-content {}
       [:p {:class "text-4xl font-bold tabular-nums"} counter]]
      [card-footer {:class "gap-2"}
       [button {:on-click #(rf/dispatch [::events/inc-counter])} "Increment"]
       [button {:variant :secondary
                :on-click #(rf/dispatch [::events/navigate :about])}
        "Go to About"]]]]))

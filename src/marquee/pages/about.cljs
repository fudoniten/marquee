(ns marquee.pages.about
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content]]))

(defn page []
  [:div {:class "space-y-6"}
   [:div
    [:h1 {:class "text-3xl font-bold tracking-tight"} "About"]
    [:p {:class "text-muted-foreground"}
     "The second example page."]]
   [card {}
    [card-header {}
     [card-title {} "Stack"]
     [card-description {} "How this page is put together."]]
    [card-content {}
     [:ul {:class "list-disc space-y-1 pl-5 text-sm"}
      [:li "re-frame for state and a tiny page router"]
      [:li "Tailwind CSS with shadcn/ui design tokens"]
      [:li "shadcn-styled Button and Card as Reagent components"]
      [:li "shadow-cljs for the build"]
      [:li "neato other stuff"]]]]
   [button {:variant :outline
            :on-click #(rf/dispatch [::events/navigate :home])}
    "Back to Home"]])

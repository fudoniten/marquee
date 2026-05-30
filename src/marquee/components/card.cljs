(ns marquee.components.card
  "shadcn/ui Card family, as Reagent components."
  (:require [marquee.lib.utils :refer [cn]]))

(defn card [{:keys [class]} & children]
  (into [:div {:class (cn "rounded-lg border bg-card text-card-foreground shadow-sm" class)}]
        children))

(defn card-header [{:keys [class]} & children]
  (into [:div {:class (cn "flex flex-col space-y-1.5 p-6" class)}] children))

(defn card-title [{:keys [class]} & children]
  (into [:h3 {:class (cn "text-2xl font-semibold leading-none tracking-tight" class)}]
        children))

(defn card-description [{:keys [class]} & children]
  (into [:p {:class (cn "text-sm text-muted-foreground" class)}] children))

(defn card-content [{:keys [class]} & children]
  (into [:div {:class (cn "p-6 pt-0" class)}] children))

(defn card-footer [{:keys [class]} & children]
  (into [:div {:class (cn "flex items-center p-6 pt-0" class)}] children))

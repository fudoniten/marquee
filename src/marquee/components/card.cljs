(ns marquee.components.card
  "shadcn/ui Card family, as Reagent components. Props besides :class
  (e.g. :on-click) are passed through to the underlying element."
  (:require [marquee.lib.utils :refer [cn]]))

(defn- with-classes [props base]
  (assoc (dissoc props :class) :class (cn base (:class props))))

(defn card [props & children]
  (into [:div (with-classes props "rounded-lg border bg-card text-card-foreground shadow-sm")]
        children))

(defn card-header [props & children]
  (into [:div (with-classes props "flex flex-col space-y-1.5 p-6")] children))

(defn card-title [props & children]
  (into [:h3 (with-classes props "text-2xl font-semibold leading-none tracking-tight")]
        children))

(defn card-description [props & children]
  (into [:p (with-classes props "text-sm text-muted-foreground")] children))

(defn card-content [props & children]
  (into [:div (with-classes props "p-6 pt-0")] children))

(defn card-footer [props & children]
  (into [:div (with-classes props "flex items-center p-6 pt-0")] children))

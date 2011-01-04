(ns handling.management
  (:import (org.zkoss.zul
            Window
            ;Groupbox
            ;Bandbox
            Image
            ;Listbox Listhead Listheader Listitem
            Columns Column
            Vbox
            Borderlayout Center West North South
            Grid Row Rows
            Label Textbox 
            Menu Menubar Menupopup Menuitem Messagebox
            Tabbox Tabs Tab Tabpanel Tabpanels
            Toolbar Toolbarbutton
            Button
            Popup))
  (:import (org.zkoss.zk.ui.event
             EventListener Event Events))
  
  (:import (org.zkoss.zk.ui Sessions))
  
  (:use
    meta.data 
    handling.crud
    handling.aspect
    views.subject))

(declare launch-sign-in load-gui) 

(defn init 
  "Initiates the application in the CLOUD"
  [component layout desktop page]
  (let [signals-map (ref {}) 
        has-record-map (ref {})
        tabs (Tabs.)
        tab-panels (Tabpanels.)
        tab-box (Tabbox.)]
    
    (letfn [(signal 
              [table-name new-rec old-rec]
              (doseq [x @signals-map]
                ((val x) table-name new-rec old-rec)))
            
            (close-signal
	            [tab]
	            (dosync 
	              (alter signals-map dissoc tab)
	              (alter has-record-map dissoc tab)))
	          
	          (gen-view
	            ([tab tab-panel signal]
	              (cascade-append
	                [tab tabs]
	                [tab-panel tab-panels])
	              (.setSelectedTab tab-box tab)
	              (dosync (alter signals-map assoc tab signal)))
	            
	            ([tab tab-panel signal record has-record?]
	              (if (not (empty? @has-record-map))
	                (loop [views (seq @has-record-map)]
	                  (if ((val (first views)) @record)
	                    (.setSelected (key (first views)) true)
	                    (if (= (first views) (last @has-record-map))
	                      (do
	                        (gen-view tab tab-panel signal)
	                        (dosync (alter has-record-map assoc tab has-record?)))
	                      (recur (next views)))))
	                (do
	                  (gen-view tab tab-panel signal)
	                  (dosync (alter has-record-map assoc tab has-record?))))))]
      
      (let [scope {:gen-view gen-view :close-signal close-signal :signal signal}]
        
        (letfn [(load-gui []
                  (let [menu-bar (Menubar.)
                        north-box (doto (Vbox.) (.setHeight "100%"))
                        tab-toolbar (Toolbar.)]
                    
                    (doseq [x types]
                      (when (:in-menu x)
                        (let [menu (doto (Menu. (:multi-name x)))
                              search-button (doto (Menuitem. "Buscar") (.setImage (:find icons)))
                              create-button (doto (Menuitem. "Crear") (.setImage (:add icons)))
                              popup (Menupopup.)]
                          
                          (if (not (nil?(:icon x)))
                            (.setImage menu ((:icon x) icons)))
                          
                          (cascade-append
                            [search-button popup menu menu-bar]
                            [create-button popup])
                          
                          (add-event search-button "onClick"
                            #(apply gen-view (gen-selector x scope)))
                          
                          ;(add-event sign-out-opt "onClick"
                          ;  #(println desktop))
                          
                          (add-event create-button "onClick"
                            #(gen-form x scope)))))
                    
                    (cascade-append
                      [(doto (Image. (:app-icon icons)) (.setHeight "50px")) north-box (doto (North.) (.setFlex true)) layout]
                      [(doto (Menuitem. "Cerrar Sesión") (.setImage (:key icons))) menu-bar north-box]
                      [tabs tab-box (doto (Center.) (.setFlex true)) layout]
                      [tab-panels tab-box]
                      [(doto (Toolbarbutton.)
                         (add-event "onClick"
                           #(Messagebox/show  
                              "Desea cerrar todas las pestañas abiertas?"
                              "Cerrar todas las pestañas"
                              (bit-xor Messagebox/YES Messagebox/NO)                                  
                              Messagebox/QUESTION
                              (proxy [EventListener] []
                                (onEvent[e]
                                  (when (= (.. e getData intValue) Messagebox/YES)
                                    (doseq [t (apply conj [] (.getChildren tabs))]
                                      (.detach t)
                                      (close-signal t))
                                    (doseq [tp (apply conj [] (.getChildren tab-panels))]
                                      (.removeChild tab-panels tp)))))))
                         (.setImage (:cancel icons))
                         (.setTooltip
                           (doto (Popup.) (.appendChild (Label. "Cerrar todas las pestañas abiertas"))))) tab-toolbar tab-box])
                    
                    (case (:view-type initial-view)
                      :selector
                      (apply gen-view (gen-selector (:entity-type initial-view) scope)))))
                
                (launch-sign-in []
                  (let [win (doto (Window. "Autenticación" "normal" true) (.setPage page) (.setMode "modal") (.setWidth "554px"))
                        box (doto (Vbox.) (.setHeight "100%"))
                        form (doto (Grid.) (.appendChild (doto (Columns.) (.appendChild (doto (Column.) (.setWidth "100px"))))))
                        user (Textbox.)
                        pass (doto (Textbox.) (.setType "password"))
                        user-row (Row.) 
                        pass-row (Row.)
                        rows (Rows.)
                        menu (Menubar.)
                        access #(if (exists? :temaworks_user {:user (.getValue user) :pass (.getValue pass)})
                                  (do
                                    (println win)
                                    (.detach win)
                                    (load-gui))
                                  (Messagebox/show  
                                    "Nombre de usuario o contraseña incorrecto."
                                    "Error"
                                    Messagebox/OK
                                    Messagebox/EXCLAMATION))]
                    (add-event user "onOK" access)
                    (add-event pass "onOK" access)
                    (cascade-append
                      [(Label. "Usuario:") user-row rows form]
                      [(Label. "Contraseña:") pass-row rows]
                      [user user-row]
                      [pass pass-row]
                      [(doto (Menuitem."Ingresar") (add-event "onClick" access) (.setImage (:signin icons))) menu box win])
                    
                    (doseq [x [(doto (Image. (:app-icon icons)) (.setWidth "554px")) form menu]] ;(.setHeight "50px")
                      (.appendChild box x))
                    ;(.setHflex form "min")
                    ))]
          (.setTitle page app-name)
          (launch-sign-in)
          ;(load-gui)
          )))))

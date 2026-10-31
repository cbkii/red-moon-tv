#!/system/bin/sh
ui_print 'Red Moon TV supervisor: optional, no package selected by default.'
ui_print 'Select one package using a4-continuity.py --supervisor enable after installation.'
set_perm "$MODPATH/service.sh" 0 0 0755

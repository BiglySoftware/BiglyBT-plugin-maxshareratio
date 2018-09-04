Share Ratio Maximizer
==========

The sole aim of this plugin is to maximize the amount of data uploaded to other peers while downloading the absolute minimum. That is, it will only download a block of a download if it thinks there is a reasonable chance of that block being uploaded more than once to other peers.

A typical use case involves trackers that enforce share-ratio limits on users. Often it is hard to maintain a decent share ratio as by the time you get to download data there are few peers and lots of other seeds. If you can get in early on a download from the tracker (while there are more seeds than peers) and enable the this feature then you have a chance of obtaining a decent share ratio. Indeed, you might decide to add fresh downloads that you have little interest in just as a means of increasing your overall share ratio.

DON'T enable this on downloads that you want to complete fast - it might not make any progress at all if there are lots of seeds and few peers... 


To use
------

Right-click on a download and select from the context menu to enable/disable the feature

There is an additional menu item for enabled downloads to stop the download once it completes.

Library views have an additional column to show whether the feature is enabled

There is a log view that gives activity information: Tools->Plugins->Log Views->Share Ration Maximizer

Configuration-wise there is just an option to specify a list of banned country codes. Note however that this results in IPs being added to the global ban list under all circumstances, not just with respect to downloads so use with care!


# Multi-NIC-Downloader

This program can use all NICs available in host it running to download files.
Users will get higher download speed(n times faster than before) with this program if they have multiple NICs on their computer.

It only supports http(s) currently.

This project consists of two modules (engine and server).

+ Engine implements main functions of this downloader.
It first put the whole file as one task piece into downloading list. 
Then launch many download workers.
Each worker maintain a socket bind to a NIC.
Workers fetch a task piece from Task.kt and download until no more piece can be fetched.
Task.kt return a task piece not used**** from downloading list or split one downloading task piece then return.
+ Server is a web GUI for this program. You can visit http://localhost:8080/ to interact with it.

You can build this project, run it and visit http://localhost:8080/.
Or just debug engine module to see the details by running engine/src/test/kotlin/cn/banjiaojuhao/downloader/engine/EngineTest.kt.

Forks and pull requests are welcomed.
package cn.banjiaojuhao.downloader.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CrossOrigin
@RestController
class DownloadController {

    @Autowired
    private val downloadService: DownloadService? = null

    @GetMapping("/state")
    fun state(): ResultMsg {
        return downloadService!!.status()
    }

    @GetMapping("/create")
    fun create(@RequestParam param: Map<String, String>): ResultMsg {
        return downloadService!!.createTask(param["url"]!!)
    }

    @GetMapping("/delete")
    fun deleteTask(@RequestParam id: Int): ResultMsg {
        return downloadService!!.deleteTask(id)
    }

//    @GetMapping("/refresh")
//    fun refresh_url(@RequestParam param: Map<String, String>): DownloadTask {
//        val mapper = ObjectMapper()
//        return downloadService!!.refresh_url(Integer.parseInt(param["url"]), param["url"])
//    }

    @GetMapping("/pause")
    fun pauseTask(@RequestParam id: Int): ResultMsg {
        return downloadService!!.pauseTask(id)
    }

    @GetMapping("/continue")
    fun continueTask(@RequestParam id: Int): ResultMsg {
        return downloadService!!.continueTask(id)
    }

}

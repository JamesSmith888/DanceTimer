package com.example.dancetimer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.DanceRecord
import com.example.dancetimer.util.DateRangeCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).danceRecordDao()

    val allRecords: Flow<List<DanceRecord>> = dao.getAll()

    /**
     * 午夜触发器：立即发射一次，之后在每个自然日的 00:00:00 再次发射。
     *
     * 所有日期相关的费用统计 Flow 均通过 [flatMapLatest] 挂载在此触发器上，
     * 确保时间边界在每个查询周期内**动态计算**而非使用构造时的快照。
     * 这从根本上消除了 ViewModel 实例跨日/跨周/跨月存活导致数据陈旧的问题。
     *
     * [SharingStarted.WhileSubscribed]：无活跃收集者时暂停上游（节省资源）；
     * [replay = 1]：新收集者订阅时立即获得最新值，无需等待下一个午夜。
     * 5 s 的 stopTimeout 防止横竖屏切换等配置变更期间不必要的重启。
     */
    private val dateTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            // 等到下一个自然日午夜；至少保留 60 s 防止时钟漂移/夏令时导致的死循环
            delay(DateRangeCalculator.msUntilTomorrow().coerceAtLeast(60_000L))
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** 今日累计费用（每日午夜自动刷新）。 */
    val todayCost: Flow<Float> = dateTicker.flatMapLatest {
        dao.getCostInRange(
            from = DateRangeCalculator.startOfToday(),
            to   = DateRangeCalculator.startOfTomorrow()
        )
    }

    /** 过去30天累计费用（每日午夜自动刷新，含今日共30个自然日，与自然月无关）。 */
    val last30DaysCost: Flow<Float> = dateTicker.flatMapLatest {
        dao.getCostInRange(
            from = DateRangeCalculator.startOf30DaysAgo(),
            to   = DateRangeCalculator.startOfTomorrow()
        )
    }

    /** 全部记录累计费用（总计）。 */
    val totalCost: Flow<Float> = dao.getTotalCost()

    fun deleteRecord(record: DanceRecord) {
        viewModelScope.launch { dao.delete(record) }
    }

    fun deleteAll() {
        viewModelScope.launch { dao.deleteAll() }
    }

    suspend fun getRecordById(id: Long): DanceRecord? = dao.getById(id)
}


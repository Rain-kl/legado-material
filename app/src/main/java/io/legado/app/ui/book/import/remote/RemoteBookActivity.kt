package io.legado.app.ui.book.import.remote

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.activity.addCallback
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

/**
 * 展示远程书籍
 */
class RemoteBookActivity : BaseImportBookActivity<RemoteBookViewModel>(),
    RemoteBookAdapter.CallBack,
    SelectActionBar.CallBack,
    ServersDialog.Callback {

    override val viewModel by viewModel<RemoteBookViewModel>()
    private val adapter by lazy { RemoteBookAdapter(this, this) }
    private var groupMenu: SubMenu? = null
    private var latestUiState = RemoteBookUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchView.queryHint = getString(R.string.screen) + " • " + getString(R.string.remote_book)
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        lifecycleScope.launch {
            if (!setBookStorage()) {
                finish()
                return@launch
            }
            initView()
            initEvent()
            launch {
                viewModel.uiState.collect { uiState ->
                    latestUiState = uiState
                    binding.refreshProgressBar.isVisible =
                        uiState.interaction.isLoading || uiState.interaction.isUploading
                    binding.tvEmptyMsg.isGone = uiState.items.isNotEmpty()
                    binding.tvGoBack.isEnabled = uiState.canGoBack
                    binding.tvPath.text = buildPath(uiState.pathNames)
                    adapter.setItems(uiState.items.map { it.remoteBook })
                    syncSelectionWithCurrentItems()
                    upCountView()
                    invalidateOptionsMenu()
                }
            }
            launch {
                viewModel.permissionDenialEvent.collect {
                    localBookTreeSelect.launch {
                        title = getString(R.string.select_book_folder)
                    }
                }
            }
            viewModel.initData {
                viewModel.refreshData()
            }
        }
    }

    override fun observeLiveBus() {
        // No-op. The permission event is collected in onCreate with lifecycleScope.
    }

    private fun initView() {
        //binding.layTop.setBackgroundColor(backgroundColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.selectActionBar.setMainActionText(R.string.add_to_bookshelf)
        binding.selectActionBar.setCallBack(this)
        if (!LocalConfig.webDavBookHelpVersionIsLast) {
            showHelp("webDavBookHelp")
        }
    }

    private fun sortCheck(sortKey: RemoteBookSort) {
        val (newSortKey, ascending) = if (latestUiState.sortKey == sortKey) {
            sortKey to !latestUiState.sortAscending
        } else {
            sortKey to true
        }
        viewModel.updateSort(newSortKey, ascending)
    }

    private fun initEvent() {
        binding.tvGoBack.setOnClickListener {
            goBackDir()
        }
    }


    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_remote, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> viewModel.refreshData()
            R.id.menu_server_config -> showDialogFragment<ServersDialog>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("webDavBookHelp")
            R.id.menu_sort_name -> {
                item.isChecked = true
                sortCheck(RemoteBookSort.Name)
                viewModel.refreshData()
            }
            R.id.menu_sort_time -> {
                item.isChecked = true
                sortCheck(RemoteBookSort.Default)
                viewModel.refreshData()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_sort)?.subMenu
        groupMenu?.setGroupCheckable(R.id.menu_group_sort, true, true)
        groupMenu?.findItem(R.id.menu_sort_name)?.isChecked =
            latestUiState.sortKey == RemoteBookSort.Name
        groupMenu?.findItem(R.id.menu_sort_time)?.isChecked =
            latestUiState.sortKey == RemoteBookSort.Default
        return super.onPrepareOptionsMenu(menu)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClickSelectBarMainAction() {
        if (adapter.selected.isEmpty()) return
        lifecycleScope.launch {
            binding.refreshProgressBar.isVisible = true
            viewModel.addToBookshelf(adapter.selected.toSet()).onSuccess {
                adapter.selected.clear()
                adapter.notifyDataSetChanged()
                upCountView()
            }
            binding.refreshProgressBar.isVisible = false
        }
    }

    private fun goBackDir(): Boolean {
        if (!latestUiState.canGoBack) {
            return false
        }
        viewModel.navigateBack()
        return true
    }

    override fun openDir(remoteBook: RemoteBook) {
        viewModel.navigateToDir(remoteBook)
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selected.size, adapter.checkableCount)
    }

    override fun onDialogDismiss(tag: String) {
        viewModel.initData {
            viewModel.refreshData()
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.setSearchKey(newText.orEmpty())
    }

    private fun showRemoteBookDownloadAlert(
        remoteBook: RemoteBook,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        alert(
            R.string.draw,
            R.string.archive_not_found
        ) {
            okButton {
                lifecycleScope.launch {
                    viewModel.addToBookshelf(setOf(remoteBook)).onSuccess {
                        onDownloadFinish?.invoke()
                    }
                }
            }
            noButton()
        }
    }

    override fun startRead(remoteBook: RemoteBook) {
        val downloadFileName = remoteBook.filename
        if (!ArchiveUtils.isArchive(downloadFileName)) {
            appDb.bookDao.getBookByFileName(downloadFileName)?.let {
                startReadBook(it)
            }
        } else {
            AppConfig.defaultBookTreeUri ?: return
            val downloadArchiveFileDoc = FileDoc.fromUri(Uri.parse(AppConfig.defaultBookTreeUri), true)
                .find(downloadFileName)
            if (downloadArchiveFileDoc == null) {
                showRemoteBookDownloadAlert(remoteBook) {
                    startRead(remoteBook)
                }
            } else {
                onArchiveFileClick(downloadArchiveFileDoc)
            }
        }
    }

    override fun onRemoteBookLongClick(remoteBook: RemoteBook) {
        appDb.bookDao.getBookByFileName(remoteBook.filename)?.let { linkedBook ->
            startActivity(
                android.content.Intent(this, BookInfoActivity::class.java).apply {
                    putExtra("name", linkedBook.name)
                    putExtra("author", linkedBook.author)
                    putExtra("bookUrl", linkedBook.bookUrl)
                }
            )
            return
        }
        toastOnUi("正在下载并解析书籍详情...")
        binding.refreshProgressBar.isVisible = true
        lifecycleScope.launch {
            viewModel.addToBookshelf(setOf(remoteBook))
            binding.refreshProgressBar.isVisible = false
            adapter.notifyDataSetChanged()
            appDb.bookDao.getBookByFileName(remoteBook.filename)?.let { newBook ->
                startActivity(
                    android.content.Intent(this@RemoteBookActivity, BookInfoActivity::class.java).apply {
                        putExtra("name", newBook.name)
                        putExtra("author", newBook.author)
                        putExtra("bookUrl", newBook.bookUrl)
                    }
                )
            } ?: toastOnUi("书籍导入完成，但未获取到详情")
        }
    }

    private fun syncSelectionWithCurrentItems() {
        val currentItems = adapter.getItems()
            .asSequence()
            .filter { !it.isDir && !it.isOnBookShelf }
            .associateBy { it.path }
        val updatedSelection = adapter.selected
            .asSequence()
            .mapNotNull { currentItems[it.path] }
            .toHashSet()
        if (updatedSelection.size != adapter.selected.size) {
            adapter.selected = updatedSelection
            adapter.notifyDataSetChanged()
        }
    }

    private fun buildPath(pathNames: List<String>): String {
        if (pathNames.isEmpty()) return File.separator
        return if (pathNames.first() == "/") {
            val rest = pathNames.drop(1).joinToString(File.separator)
            if (rest.isEmpty()) File.separator else File.separator + rest + File.separator
        } else {
            pathNames.joinToString(File.separator, postfix = File.separator)
        }
    }
}

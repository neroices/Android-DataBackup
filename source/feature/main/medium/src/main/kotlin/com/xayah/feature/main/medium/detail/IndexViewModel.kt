package com.xayah.feature.main.medium.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import com.xayah.core.common.viewmodel.BaseViewModel
import com.xayah.core.common.viewmodel.IndexUiEffect
import com.xayah.core.common.viewmodel.UiIntent
import com.xayah.core.common.viewmodel.UiState
import com.xayah.core.data.repository.ContextRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.model.DefaultPreserveId
import com.xayah.core.model.OpType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.route.MainRoutes
import com.xayah.feature.main.medium.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.xayah.core.service.medium.backup.LocalService as LocalBackupService
import com.xayah.core.service.medium.restore.LocalService as LocalRestoreService

data class IndexUiState(
    val isRefreshing: Boolean,
    val name: String,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object OnRefresh : IndexUiIntent()
    data class UpdateMedia(val mediaEntity: MediaEntity) : IndexUiIntent()
    data class BackupToLocal(val mediaEntity: MediaEntity, val navController: NavHostController) : IndexUiIntent()
    data class RestoreFromLocal(val mediaEntity: MediaEntity, val navController: NavHostController) : IndexUiIntent()
    data class Preserve(val mediaEntity: MediaEntity) : IndexUiIntent()
    data class Delete(val mediaEntity: MediaEntity) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    args: SavedStateHandle,
    private val mediaRepo: MediaRepository,
    rootService: RemoteRootService,
    private val localBackupService: LocalBackupService,
    private val localRestoreService: LocalRestoreService,
    private val contextRepository: ContextRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        isRefreshing = false,
        name = args.get<String>(MainRoutes.ArgMediaName) ?: "",
    )
) {
    init {
        rootService.onFailure = {
            val msg = it.message
            if (msg != null)
                emitEffect(IndexUiEffect.ShowSnackbar(message = msg))
        }
    }

    @DelicateCoroutinesApi
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.OnRefresh -> {
                emitStateSuspend(state.copy(isRefreshing = true))
                mediaRepo.refreshFromLocalMedia(state.name)
                mediaRepo.updateMediaDataSize(OpType.BACKUP, 0, state.name)
                mediaRepo.updateMediaArchivesSize(OpType.RESTORE, state.name)
                emitStateSuspend(state.copy(isRefreshing = false))
            }

            is IndexUiIntent.UpdateMedia -> {
                mediaRepo.upsert(intent.mediaEntity)
            }

            is IndexUiIntent.BackupToLocal -> {
                launchOnGlobal {
                    emitEffectSuspend(IndexUiEffect.DismissSnackbar)
                    emitEffectSuspend(
                        IndexUiEffect.ShowSnackbar(
                            message = contextRepository.getString(R.string.task_is_in_progress),
                            actionLabel = contextRepository.getString(R.string.details),
                            duration = SnackbarDuration.Indefinite,
                            onActionPerformed = {
                                withMainContext {
                                    intent.navController.navigate(MainRoutes.TaskList.route)
                                }
                            }
                        )
                    )
                    mediaRepo.upsert(intent.mediaEntity.copy(extraInfo = intent.mediaEntity.extraInfo.copy(activated = true)))
                    localBackupService.preprocessing()
                    localBackupService.processing()
                    localBackupService.postProcessing()
                    localBackupService.destroyService()

                    emitEffectSuspend(IndexUiEffect.DismissSnackbar)
                    emitEffectSuspend(
                        IndexUiEffect.ShowSnackbar(
                            message = contextRepository.getString(R.string.backup_completed),
                            actionLabel = contextRepository.getString(R.string.details),
                            duration = SnackbarDuration.Long,
                            onActionPerformed = {
                                withMainContext {
                                    intent.navController.navigate(MainRoutes.TaskList.route)
                                }
                            }
                        )
                    )
                }
            }

            is IndexUiIntent.RestoreFromLocal -> {
                launchOnGlobal {
                    emitEffectSuspend(IndexUiEffect.DismissSnackbar)
                    emitEffectSuspend(
                        IndexUiEffect.ShowSnackbar(
                            message = contextRepository.getString(R.string.task_is_in_progress),
                            actionLabel = contextRepository.getString(R.string.details),
                            duration = SnackbarDuration.Indefinite,
                            onActionPerformed = {
                                withMainContext {
                                    intent.navController.navigate(MainRoutes.TaskList.route)
                                }
                            }
                        )
                    )
                    mediaRepo.upsert(intent.mediaEntity.copy(extraInfo = intent.mediaEntity.extraInfo.copy(activated = true)))
                    localRestoreService.preprocessing()
                    localRestoreService.processing()
                    localRestoreService.postProcessing()
                    localRestoreService.destroyService()

                    emitEffectSuspend(IndexUiEffect.DismissSnackbar)
                    emitEffectSuspend(
                        IndexUiEffect.ShowSnackbar(
                            message = contextRepository.getString(R.string.restore_completed),
                            actionLabel = contextRepository.getString(R.string.details),
                            duration = SnackbarDuration.Long,
                            onActionPerformed = {
                                withMainContext {
                                    intent.navController.navigate(MainRoutes.TaskList.route)
                                }
                            }
                        )
                    )
                }
            }

            is IndexUiIntent.Preserve -> {
                mediaRepo.preserve(intent.mediaEntity)
            }

            is IndexUiIntent.Delete -> {
                mediaRepo.delete(intent.mediaEntity)
            }
        }
    }

    private val _activatedCount: Flow<Long> = mediaRepo.activatedCount.flowOnIO()
    val activatedState: StateFlow<Boolean> = _activatedCount.map { it != 0L }.stateInScope(false)

    private val _backupItem: Flow<MediaEntity?> = mediaRepo.queryFlow(
        name = uiState.value.name,
        opType = OpType.BACKUP,
        preserveId = DefaultPreserveId
    ).flowOnIO()
    val backupItemState: StateFlow<MediaEntity?> = _backupItem.stateInScope(null)

    private val _restoreItems: Flow<List<MediaEntity>> = mediaRepo.queryFlow(
        name = uiState.value.name,
        opType = OpType.RESTORE,
    ).map { medium -> medium.sortedBy { it.preserveId } }.flowOnIO()
    val restoreItemsState: StateFlow<List<MediaEntity>> = _restoreItems.stateInScope(listOf())
}

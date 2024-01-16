package controls

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import controls.VisibilityState.Initial
import controls.VisibilityState.ToClose
import controls.VisibilityState.ToPopUp
import kotlinx.coroutines.delay


@Stable
private data class WithVisibility<T>(
    val data: T,
    val state: VisibilityState = Initial
)

/**
 * The state of each widget in an [AnimatedColumn].
 * @property Initial the state of all widgets that are passed to the column at the first composition.
 * @property ToPopUp the state that is applied to all widgets that are new compared to the previous version of the list.
 * @property ToClose the state of all widgets that are missing in the new version of the list.
 * When marked with this state, a closing animation will be launched for its widget.
 * After the animation is complete, the item of this widget is deleted.
 */
@Stable
enum class VisibilityState {
    Initial,
    ToPopUp,
    ToClose;

    fun toInitialState(): Boolean = when (this) {
        Initial -> true
        ToClose -> true
        ToPopUp -> false
    }

    fun toTargetState(): Boolean = when (this) {
        Initial -> true
        ToClose -> false
        ToPopUp -> true
    }
}

@Stable
data class ColumnChange<T>(
    val removed: Iterable<IndexedValue<T>>,
    val added: Iterable<IndexedValue<T>>
)

private fun <T> findDifference(
    initial: List<WithVisibility<T>>,
    changed: List<T>,
    keyProvider: (T) -> String,
): ColumnChange<T> {
    val initialTransformed = initial.map { it.data }
    val removedIndices = processChanges(changed, keyProvider, initialTransformed)
    val addedIndices = processChanges(initialTransformed, keyProvider, changed)
    return ColumnChange(removedIndices, addedIndices)
}

private fun <T> processChanges(
    changed: List<T>,
    keyProvider: (T) -> String,
    initialTransformed: List<T>
): MutableList<IndexedValue<T>> {
    val removedIndices = mutableListOf<IndexedValue<T>>()
    val mapChanged = changed.withIndex().associate { keyProvider(it.value) to it.index }
    initialTransformed.forEachIndexed { index, value ->
        if (mapChanged[keyProvider(value)] == null) {
            removedIndices.add(IndexedValue(index, value))
        }
    }
    return removedIndices
}


private fun <T> List<T>.toVisibleItems() = map { WithVisibility(it) }


/**
 *  A non-scrollable column with items addition and deletion animations.
 *  When a new list of items is fed into this composable, it detects new items and runs appearance animations for them.
 *  It also detects deleted items and removes them dynamically with animation.
 *  Mind that this column is not lazy.
 *
 *  @param items a list of items of any type ([T]).
 *  @param keyProvider a lambda that outputs a key that an item can be uniquely identified with.
 *  Duplicate keys will not crash your application as with an [androidx.compose.foundation.lazy.LazyColumn], but may produce incorrect behaviour.
 *  @param modifier the [Modifier] to be applied to the column.
 *  @param enterTransition the [EnterTransition] to be applied to each entering widget.
 *  @param exitTransition the [ExitTransition] to be applied to each exiting widget.
 *  @param itemContent an individual widget for an item of type [T]
 */
@Composable
fun <T : Any> AnimatedColumn(
    items: List<T>,
    keyProvider: (T) -> String,
    modifier: Modifier = Modifier,
    enterTransition: AwaitedEnterTransition = defaultEnterTransition,
    exitTransition: AwaitedExitTransition = defaultExitTransition,
    itemContent: @Composable AnimatedVisibilityScope.(Int, T) -> Unit
) = AnimatedColumnImpl(
    items = items,
    keyProvider = keyProvider,
    modifier = modifier,
    enterTransition = enterTransition,
    exitTransition = exitTransition,
    isLazy = false
) { index, item ->
    itemContent(index, item)
}


/**
 *  Absolutely the same as [AnimatedColumn], but backed by a lazy column.
 *
 *  @param items a list of items of any type ([T]). The items should be wrapped in a [State] to keep them a stable argument.
 *  @param keyProvider a lambda that outputs a key that an item can be uniquely identified with.
 *  @param modifier the [Modifier] to be applied to the column.
 *  @param enterTransition the [EnterTransition] to be applied to each entering widget.
 *  @param exitTransition the [ExitTransition] to be applied to each exiting widget.
 *  @param itemContent an individual widget for an item of type [T]
 */
@Composable
fun <T : Any> LazyAnimatedColumn(
    items: List<T>,
    keyProvider: (T) -> String,
    modifier: Modifier = Modifier,
    lazyModifier: LazyItemScope.() -> Modifier = { Modifier },
    enterTransition: AwaitedEnterTransition = defaultEnterTransition,
    exitTransition: AwaitedExitTransition = defaultExitTransition,
    itemContent: @Composable AnimatedVisibilityScope.(Int, T) -> Unit
) = AnimatedColumnImpl(
    items = items,
    keyProvider = keyProvider,
    modifier = modifier,
    lazyModifier = lazyModifier,
    enterTransition = enterTransition,
    exitTransition = exitTransition,
    isLazy = true
) { index, item ->
    itemContent(index, item)
}

@Composable
private fun <T> AnimatedColumnImpl(
    items: List<T>,
    keyProvider: (T) -> String,
    modifier: Modifier = Modifier,
    lazyModifier: LazyItemScope.() -> Modifier = { Modifier },
    enterTransition: AwaitedEnterTransition = defaultEnterTransition,
    exitTransition: AwaitedExitTransition = defaultExitTransition,
    isLazy: Boolean = false,
    itemContent: @Composable AnimatedVisibilityScope.(Int, T) -> Unit
) {
    val internalList = remember {
        items.toVisibleItems().toMutableStateList()
    }

    LaunchedEffect(items) {
        val (removedItems, addedItems) = findDifference(internalList, items, keyProvider)
        val total = removedItems + addedItems
        if (total.isNotEmpty()) {
            internalList.run {
                for (removed in removedItems) {
                    this[removed.index] = this[removed.index].copy(state = ToClose)
                }
                for (added in addedItems) {
                    add(added.index, WithVisibility(added.value, ToPopUp))
                }
            }
            delay(maxOf(enterTransition.duration, exitTransition.duration).toLong())
            internalList.clearUpdate(items)
        } else {
            internalList.clearUpdate(items)
        }
    }

    if (isLazy) {
        LazyColumn(modifier = modifier) {
            items(
                count = internalList.size,
                key = { keyProvider(internalList[it].data) }
            ) { index ->
                val internalLazyModifier = lazyModifier()
                val currentItem = internalList[index]
                AnimatedRemovable(
                    key = keyProvider(currentItem.data),
                    state = currentItem.state,
                    modifier = internalLazyModifier,
                    enterTransition = enterTransition.transition,
                    exitTransition = exitTransition.transition
                ) {
                    itemContent(index, currentItem.data)
                }
            }
        }
    } else {
        Column(modifier) {
            internalList.forEachIndexed { index, item ->
                AnimatedRemovable(
                    key = keyProvider(item.data),
                    state = item.state,
                    enterTransition = enterTransition.transition,
                    exitTransition = exitTransition.transition
                ) {
                    itemContent(index, item.data)
                }
            }
        }
    }
}

private fun <T> SnapshotStateList<WithVisibility<T>>.clearUpdate(
    items: List<T>
) {
    clear()
    addAll(items.toVisibleItems())
}

val testCases = listOf(
    List(4) { "Item $it" } + listOf("Item 8", "Item 9"),
    List(12) { "Item $it" },
    List(12) { "Item $it" }.shuffled(),
    List(20) { "Item $it" },
    listOf("Item 1")
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Preview("AnimatedColumnPreview")
fun AnimatedColumnPreview() {
    var clickCount by remember {
        mutableIntStateOf(0)
    }
    var exampleList by remember {
        mutableStateOf(List(10) { "Item $it" })
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyAnimatedColumn(
            items = exampleList,
            keyProvider = { it },
            modifier = Modifier.heightIn(10.dp, LocalConfiguration.current.screenHeightDp.dp),
            lazyModifier = { Modifier.animateItemPlacement(tween(1000)) },
            enterTransition = defaultEnterTransition * 2,
            exitTransition = defaultExitTransition * 2
        ) { _, item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 20.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.LightGray, Color.White))
                    )
            ) {
                Text(
                    text = item,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.White)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    exampleList = testCases[clickCount % 5]
                    clickCount++
                }
            ) {
                Text("Change elements")
            }
        }
    }
}


@Composable
internal fun AnimatedRemovable(
    key: String,
    state: VisibilityState,
    modifier: Modifier = Modifier,
    enterTransition: EnterTransition = defaultEnterTransition.transition,
    exitTransition: ExitTransition = defaultExitTransition.transition,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val internalVisible = remember(key) {
        MutableTransitionState(state.toInitialState())
    }
    LaunchedEffect(state) {
        internalVisible.targetState = state.toTargetState()
    }
    AnimatedVisibility(
        visibleState = internalVisible,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier
    ) {
        Column {
            content()
        }
    }
}
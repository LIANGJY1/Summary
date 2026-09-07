**Q1: 为什么移动端需要架构？它要解决怎样的工程痛点？**

- **移动端早期的"伪需求"**：在移动端发展早期，我们通常会提及 App 的架构，此时总有些大材小用的感觉，因为移动端并没有复杂的业务处理、高并发等场景，甚至我们需要的只是简单地"将数据展示在屏幕上"。
- **随着移动端飞速发展产生的问题**：
  - 移动端 App 中业务逻辑越来越复杂，用户渴望更好的体验及更新颖的功能；
  - 不断地迭代让项目结构复杂化，维护成本越来越高。
- **架构的核心目的**：我们需要一个良好的架构模式，**拆分视图和数据，解除模块之间的耦合，提高模块内部的聚合度**，让系统更稳健。本章谈论的架构，即是对客户端的**代码组织/职责**进行的划分。
- **本章主线**：以传统的 MVC 及当下流行的 MVP、MVVM 架构为例，展现 Kotlin 在实现这些架构时的魅力；同时介绍一种比较新颖的事物——**基于单向数据流的 Android 架构**，并基于一个名为 ReKotlin 的开源项目来实现一个完整的 Android 架构。

**Q2: 什么是 MVC 架构？三个角色各承担什么职责？（12.1.1）**

- **起源**：Android 架构的鼻祖，自然是经典的 MVC 了。在用户界面比业务逻辑更容易发生变化的时候，客户端和后端开发需要一种分离用户界面功能的方式，这时候，MVC 模式应运而生。MVC 对应 Model、View、Controller。
- **Model（数据层）**：负责管理业务逻辑和处理网络或数据库 API。
- **View（视图层）**：让数据层的数据可视化。在 Android 中对应**用户交互、UI 绘制**等。
- **Controller（逻辑层）**：获得用户行为的通知，并根据需要更新 Model。
- **对 Model 的常见误解**：很多人对于经典 MVC 架构中的 Model 一直存在误解，认为其代表的只是一个实体模型。其实，准确来说它**还应该包含大量的业务逻辑处理**。相对而言，Controller 只是在 View 和 Model 层之间建立一个桥梁而已。
- **三层结构细分**：
  - **Model 层**：数据访问（数据库、文件、网络等）、缓存（图片、文件等）、配置文件（shared preference）等；
  - **View 层**：数据展示与管理、用户交互、UI 组件的绘制、列表 Adapter 等；
  - **Controller 层**：初始化配置（定义全局变量等）、数据加工（加工成 UI 层需要的数据）、数据变化的通知机制等。

**Q3: 在 Android 中 Activity 到底应该归入哪一层？为什么？**

- **历史现状**：当你在 Stack Overflow 中搜索类似"如何在 Android 应用中使用 Activity"的问题时，你会发最高频的答案就是：**一个 Activity 既是 View 又是 Controller**。
- **背后的妥协**：这看起来好像对新手非常不友好，但是当时解决的**重点问题是使 Model 可测试**。这导致很多开发者在项目结构中出现了很多 Free Style 的代码，使得 Activity 中代码量庞大并且难以维护。
- **经验结论**：经过大量时间与项目的验证，我们更加明确：**Activities、Fragments 和 Views 都应该被划分到 MVC 的 View 层中，而不是 Controller 或 Model 中**。也就是说，Activity/Fragment 只负责展示数据与接收用户交互，具体的业务逻辑应该交给独立出来的层去处理。

**Q4: MVC 架构的优势有哪些？**

- **Model 层可单元测试**：Model 类没有对 Android 类的任何引用，因此可以直接进行单元测试。
- **Controller 层可单元测试**：Controller 不会扩展或实现任何 Android 类，并且应该引用 View 的接口类。通过这种方式，也可以对控制器进行单元测试。
- **View 层遵循单一职责原则**：如果 View 遵循单一职责原则，那么它们的角色就是为每个用户事件更新 Controller，只显示 Model 中的数据，而**不实现任何业务逻辑**。在这种理想的作用（理想情形）下，UI 测试应该足以覆盖所有的 View 的功能。
- **总结**：MVC 模式高度支持职责的分离。这种优势不仅增加了代码的可测试性，而且使其更容易扩展，从而可以相当容易地实现新功能。

**Q5: 经典 MVC 容易产生哪些问题？（Android 中 MVC 的痛点）**

- **代码相对冗余**：MVC 模式中 View 对 Model 是有着强依赖的。当 View 非常复杂的时候，为了最小化 View 中的逻辑，Model 应该能够为要显示的每个视图提供可测试的方法——这将增加大量的类和方法。
- **灵活性较低**：由于 View 依赖于 Controller 和 Model，UI 逻辑中的一个更改可能导致需要修改很多类，这降低了灵活性，并且导致 UI 难以测试。
- **可维护性低**：Android 的视图组件中，有着非常明显的生命周期，如 Activity、Fragment 等。对于 MVC 模式，我们有时不得不将处理视图逻辑的代码都写在这些组件中，造成它们十分臃肿。
- **结论**：Android 中最初的 MVC 架构问题显而易见：**过于臃肿的 Controller 层大大降低了工程的可维护性及可测试性**。

**Q6: 什么是 MVP？它相对于 MVC 的核心改进是什么？（12.1.2）**

- **定义**：直到 MVP 架构模式的出现，传统 MVC 架构才从真正意义上得到解脱。MVP 分别对应 Model、View、Presenter。
- **Model（数据层）**：负责管理业务逻辑和处理网络或数据库 API。
- **View（视图层）**：显示数据并将用户操作的信息通知给 Presenter。
- **Presenter（逻辑层）**：从 Model 中检索数据，应用 UI 逻辑并管理 View 的状态，决定显示什么，以及对 View 的事件做出响应。
- **核心改进（为什么引入 Presenter）**：相对于 MVC，MVP 模式设计思路的核心是**提出了 Presenter 层**，它是 View 层与 Model 层沟通的桥梁，对业务逻辑进行处理。这更符合了我们理想中的单一职责原则。
- **数据流方向**：View 不再直接依赖 Model，而是"用户操作 View → View 通知 Presenter → Presenter 从 Model 取数据并处理 → 驱动 View 更新"，形成了一条清晰的职责链。

**Q7: 传统 MVP 中 Model 层是如何设计的？（以 todo-app 获取任务列表为例）**

- **背景**：Android 架构蓝图中的 todo-app 允许用户创建、读取、更新和删除"待办事项"任务，以及对任务列表进行分类显示。
- **双数据源设计**：处理 Model 的时候，一般都会使用**远程和本地数据源**来获取和保存数据。以获取待办事项列表为例：当请求列表数据时，Model 优先尝试从本地获取，如果为空，则查询网络更新本地数据并返回。
  ```kotlin
  fun getTasks(callback: TasksDataSource.LoadTasksCallback) {
      // 如果本地有缓存并且缓存正常，则直接返回缓存
      if (cachedTasks.isNotEmpty() && !cacheIsDirty) {
          callback.onTasksLoaded(ArrayList(cachedTasks.values))
          return
      }
      if (cacheIsDirty) {
          // 如果缓存过期或被污染，则需要从服务端获取最新的数据
          getTasksFromRemoteDataSource(callback)
      } else {
          // 如果本地存在缓存数据则从本地获取，否则从服务端获取
          tasksLocalDataSource.getTasks(object : TasksDataSource.LoadTasksCallback {
              override fun onTasksLoaded(tasks: List<Task>) {
                  refreshCache(tasks)
                  callback.onTasksLoaded(ArrayList(cachedTasks.values))
              }
              override fun onDataNotAvailable() {
                  getTasksFromRemoteDataSource(callback)
              }
          })
      }
  }
  ```
- **为什么容易测试**：它接收通用回调类型 `TasksDataSource.LoadTasksCallback` 作为参数，使其**完全独立于任何 Android 类**，因此易于使用 JUnit 进行单元测试。
- **Mock 测试示例**：例如要模拟本地数据不准确的情况：
  ```kotlin
  private lateinit var tasksRepository: TasksRepository

  @Mock private lateinit var loadTasksCallback: TasksDataSource.LoadTasksCallback
  @Mock private lateinit var tasksRemoteDataSource: TasksDataSource
  @Mock private lateinit var tasksLocalDataSource: TasksDataSource

  private val TASKS = Lists.newArrayList(
      Task(TASK_TITLE_1, TASK_GENERIC_DESCRIPTION),
      Task(TASK_TITLE_2, TASK_GENERIC_DESCRIPTION))

  @Before fun setupTasksRepository() {
      MockitoAnnotations.initMocks(this)
      tasksRepository = TasksRepository.getInstance(tasksRemoteDataSource, tasksLocalDataSource)
  }

  @Test fun getTasksWithLocalDataSourceUnavailable_tasksAreRetrievedFromRemote() {
      tasksRepository.getTasks(loadTasksCallback)
      setTasksNotAvailable(tasksLocalDataSource)
      setTasksAvailable(tasksRemoteDataSource, TASKS)
      verify(loadTasksCallback).onTasksLoaded(TASKS)
  }
  ```

**Q8: 在 Kotlin 中如何实现 MVP 的 View 与 Presenter？**

- **View 的归类**：在界面展示数据的时候，View 通过 Presenter 来发送获取数据的指令。在 MVP 模式中，**Activity、Fragment 和自定义视图都被归为 View**。
- **BaseView 接口**：Todo 项目中，所有 View 都实现了允许设置 Presenter 的 BaseView 接口：
  ```kotlin
  interface BaseView<T> {
      var presenter: T
  }
  ```
- **契约类 TasksContract**：通常把 View 和 Presenter 的接口写在其中，便于管理：
  ```kotlin
  interface TasksContract {
      interface View : BaseView<Presenter> {
          fun showTasks(tasks: List<Task>)
          fun showTaskDetailsUi(taskId: String)
          fun showLoadingTasksError()
          fun showNoTasks()
          ......
      }
      interface Presenter : BasePresenter {
          fun loadTasks(forceUpdate: Boolean)
          ......
      }
  }
  ```
- **Fragment 中延迟初始化的 presenter**：View 模块通常在生命周期函数 `onResume()` 中通知 Presenter"我准备好被更新了，请随时下达指令"。而在 Kotlin 中，通常的做法是在 View 中声明一个**延迟初始化的 presenter**：
  ```kotlin
  class TasksFragment : Fragment(), TasksContract.View {
      override lateinit var presenter: TasksContract.Presenter
      ......
      override fun onResume() {
          super.onResume()
          presenter.start() // 请求加载当前视图初始化需要的数据
      }
  }
  ```
- **Activity 中组装**：在承载视图的 TasksActivity 上，我们初始化视图 TasksFragment 以及 TasksPresenter：
  ```kotlin
  class TasksActivity : AppCompatActivity() {
      ......
      private lateinit var tasksPresenter: TasksPresenter
      override fun onCreate(savedInstanceState: Bundle?) {
          val tasksFragment = supportFragmentManager
              .findFragmentById(R.id.contentFrame) as TasksFragment?
              ?: TasksFragment.newInstance().also {
                  replaceFragmentInActivity(it, R.id.contentFrame)
              }
          // 创建 presenter
          tasksPresenter = TasksPresenter(
              Injection.provideTasksRepository(applicationContext), tasksFragment)
          // 加载历史数据
          ......
      }
  }
  ```
- **init() 中的玄机（绑定技巧）**：看上去上面并没有将 Presenter 和 View 绑定的操作，其实 TasksPresenter 中另有玄机——得益于 `init()`，在 TasksPresenter 初始化的同时，也对 View 中的 presenter 进行赋值，这样就不必每次都写 subscribe 和 unsubscribe 方法了。当然，利用依赖注入（比如 dagger）也能实现这样的需求：
  ```kotlin
  class TasksPresenter(
      val tasksRepository: TasksRepository,
      val tasksView: TasksContract.View
  ) : TasksContract.Presenter {
      init {
          tasksView.presenter = this
      }
      override fun start() {
          loadTasks(false)
      }
      ......
  }
  ```
- **防止内存泄漏**：当页面结束的时候会终止网络请求，我们应该及时释放 Presenter 中的引用，防止内存泄漏。通常使用的是 RxLifeCycle。此外，还能通过结合很多框架（如 dagger、rxKotlin）来让工程更加通透。

**Q9: MVP 模式容易产生哪些问题？**

- **1）接口粒度难以掌控**：MVP 模式将模块职责进行了良好的分离。但在开发小规模 App 或原型时，这似乎增加了开销——对于每个业务场景，我们都要写 Activity-View-Presenter-Contract 这 4 个类。为了缓解这种情况，一些开发者删除了 Contract 接口类和 Presenter 的接口。另外，Presenter 与 View 的交互是通过接口实现的，如果**接口粒度过大，解耦程度就不高**；反之会造成**接口数量暴增**的情况。从工程的严谨角度来说，这或许并不是缺点，只是创造一个良好工程架构带来的额外工作量。
- **2）Presenter 逻辑容易过重**：当我们将 UI 的逻辑移动到 Presenter 中时，Presenter 变成了有数千行代码的类，或许会难以维护。要解决这个问题，我们只可能更多地拆分代码，创建便于单元测试的单一职责的类。
- **3）Presenter 和 View 相互引用**：我们在 Presenter 和 View 中都会保持一份对对方的引用，所以需要用 subscribe 和 unsubscribe 来绑定和解除绑定。在操作 UI 的时候，我们需要判断 UI 生命周期，否则容易造成内存泄露。
- **引出下一步**：当然，以上的"缺点"我们都可以通过良好的编码习惯及严谨的设计来规避。如果我们想要一个**基于事件且 View 会对事件变化做出反应**的架构，该怎么实现呢？这就引出了 MVVM。

**Q10: 什么是 MVVM？它与 MVP 的根本区别是什么？（12.1.3）**

- **维基百科定义**：MVVM 有助于将图形用户界面的开发与业务逻辑或后端逻辑（数据模型）的开发分离开来，这是通过置标语言（标记语言）或 GUI 代码实现的。MVVM 的视图模型是一个**值转换器**，这意味着视图模型负责从模型中暴露（转换）数据对象，以便轻松管理和呈现对象。在这方面，视图模型比视图做得更多，并且处理大部分视图的显示逻辑。视图模型可以实现**中介者模式**，组织对视图所支持的用例集的后端逻辑的访问。
- **主要构成**：MVVM 也被称为 model-view-binder。
  - **Model（数据模型）**：与 ViewModel 配合，可以获取和保存数据；
  - **View（视图）**：即将用户的动作通知给 ViewModel（视图模型）；
  - **ViewModel（视图模型）**：暴露公共属性与 View 相关的数据流，通常为 Model 和 View 的绑定关系。
- **与 MVP 的相似与不同（核心区别）**：作为 MV* 家族的一员，它看起来与 MVP 模式有所相似：它们都擅长抽象视图行为和状态。
  - 如果 MVP 模式意味着 Presenter **直接告诉 View 要显示的内容**；
  - 那么 MVVM 中，ViewModel 会**公开 Views 可以绑定的事件流**。这样，ViewModel 不再需要保持对 View 的引用，但发挥了 Presenter 一样的作用。这也意味着 **MVP 模式所需的所有接口现在都被删除了**——这对介意接口数量过多的开发者来说是个福音。
- **双向数据绑定与多对一关系**：View 还会通知 ViewModel 进行不同的操作。因此，MVVM 模式支持 View 和 ViewModel 之间的**双向数据绑定**，并且 View 和 ViewModel 之间存在**多对一**关系。View 具有对 ViewModel 的引用，但 ViewModel 没有关于 View 的信息。因为数据的使用者应该知道生产者，但生产者 ViewModel 不需要知道、也不关心谁使用数据。

**Q11: MVVM 中 Data Binding 是如何实现双向数据绑定的？（以 addtask_frag.xml 为例）**

- **背景**：光有概念部分读者可能还不能感受到 MVVM 的特点。以官方 todo-app 中的 addTask 模块为例，先看它的布局 addtask_frag.xml：
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <layout xmlns:android="http://schemas.android.com/apk/res/android"
      xmlns:app="http://schemas.android.com/apk/res-auto">
      <data>
          <import type="android.view.View"/>
          <variable
              name="viewmodel"
              type="com.example.android.architecture.blueprints.todoapp.addedittask.AddEditTaskViewModel"/>
      </data>

      <EditText
          android:id="@+id/add_task_title"
          android:layout_width="match_parent"
          android:layout_height="wrap_content"
          android:hint="@string/title_hint"
          android:singleLine="true"
          android:text="@={viewmodel.title}"/>

      <EditText
          android:id="@+id/add_task_description"
          android:layout_width="match_parent"
          android:layout_height="350dp"
          android:gravity="top"
          android:hint="@string/description_hint"
          android:text="@={viewmodel.description}"/>
  </layout>
  ```
- **<data> 标签的含义**：之前没有接触过 MVVM 模式的读者，应该会对 `<data>` 标签感到疑惑。这其实是 **Data Binding** 的一种特性：使用 Data Binding 让 xml 绑定数据，我们需要以 `<layout>` 为根布局，并且声明 `<data>`，其中 `type` 对应 Model（需要指定完整类名），`name` 相当于 Model 在当前视图中对应的对象。
- **双向绑定的表现**：我们在 xml 中就可以用 `android:text="@={viewmodel.description}"` 实现绑定。当 `viewmodel.description` 变化的时候，EditText 也会改变；反之，当我们编辑 EditText 的时候，`viewmodel.description` 的值也会相应变化。这正是 MVVM 与 MVP 最直观的差异——**数据驱动 UI，UI 的变更也自动回写到数据**。

**Q12: MVVM 中 ViewModel 是如何实现的？（AddEditTaskViewModel 与 Fragment 的绑定）**

- **ViewModel 的设计**：将大部分操作数据的逻辑都放在这个类中，在维护的时候就能体会到其中的优势。它通过 Observable 字段暴露数据，完全不需要持有 View 引用：
  ```kotlin
  class AddEditTaskViewModel internal constructor(
      context: Context,
      private val mTasksRepository: TasksRepository
  ) : TasksDataSource.GetTaskCallback {
      val title = ObservableField<String>()
      val description = ObservableField<String>()
      val dataLoading = ObservableBoolean(false)
      val snackbarText = ObservableField<String>()

      private val mContext: Context // 避免内存泄漏，我们应该使用Application的Context
      private var mTaskId: String? = null
      private var isNewTask: Boolean = false
      private var mIsDataLoaded = false
      private var mAddEditTaskNavigator: AddEditTaskNavigator? = null

      init {
          mContext = context.applicationContext // 强制使用Application的context
      }

      fun onActivityCreated(navigator: AddEditTaskNavigator) {
          mAddEditTaskNavigator = navigator
      }

      fun onActivityDestroyed() {
          // 释放不需要的引用
          mAddEditTaskNavigator = null
      }

      fun start(taskId: String?) {
          if (dataLoading.get()) {
              return
          }
          mTaskId = taskId
          if (taskId == null) {
              isNewTask = true
              return
          }
          if (mIsDataLoaded) {
              return
          }
          isNewTask = false
          dataLoading.set(true)
          mTasksRepository.getTask(taskId, this)
      }

      override fun onTaskLoaded(task: Task) {
          title.set(task.title)
          description.set(task.description)
          dataLoading.set(false)
          mIsDataLoaded = true
          // 这里我们不需要像MVP模式那样主动改变View，因为我们已经使用了Observable
      }
      ......
  }
  ```
- **Fragment 中绑定 ViewModel 的两步**：
  1. 通过 View 来创建一个 ViewDataBinding 的对象；
  2. 将 mViewModel 赋值到 XML 文件 `<data>` 里声明的 ViewModel 的具体对象当中，从而使 ViewModel 和 XML 文件创建关联：
  ```kotlin
  class AddEditTaskFragment : Fragment() {
      private var mViewModel: AddEditTaskViewModel? = null
      private lateinit var mViewDataBinding: AddtaskFragBinding
      ......
      override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?,
                                savedInstanceState: Bundle?): View? {
          val root = container?.inflate(R.layout.addtask_frag)
          mViewDataBinding = AddtaskFragBinding.bind(root)
          mViewDataBinding.viewmodel = mViewModel
          setHasOptionsMenu(true)
          retainInstance = false
          return mViewDataBinding.root
      }
      ......
  }
  ```
- **与 MVP 的对比**：与 MVP 类似，我们在 Fragment 的各个生命周期中，调用 mViewModel 对应的方法来响应 View 的变化。**不同的是**，在 MVVM 中我们只需要改变 viewModel 中的数据，View 的响应已经自动完成了（比如通过 Data Binding）。这样代码的结构比之前更加通透，我们核心关注的就是**数据的改变**。

**Q13: MVVM 容易造成哪些问题？**

- **1）需要更多精力定位 Bug**：由于双向绑定，视图中的异常排查起来会比较麻烦，你需要检查 View 中的代码，还需要检查 Model 中的代码。另外你可能多处复用了 Model，一个地方导致的异常可能会扩散到其他地方，定位错误源可能并不会太简单。
- **2）通用的 View 需要更好的设计**：当一个 View 要变成通用组件时，该 View 对应的 Model 通常不能复用。在整体架构设计不够完善时，我们很容易创建一些冗余的 Model。
- **解决思路**：如果说双向数据流这种"自动管理状态"的特性会给我们造成困扰，除了在编码上规避，还有其他的解决方案吗？答案是肯定的，这里我们推荐使用**谷歌官方的 Android Architecture Components**。

**Q14: 什么是单向数据流模型？Flux 由哪四部分组成？（12.2）**

- **引出**：既然有双向数据绑定的架构 MVVM，那自然少不了单向数据流。如果你接触过前端，你肯定听说过 **Flux**，它是最经典的单向数据流架构之一。
- **Flux 的 4 个组成部分**：
  - **View（视图）**：显示 UI；
  - **Action（动作）**：用户操作界面时，视图层发出的消息（比如用户点击按钮、输入文字等）；
  - **Dispatcher（分发器）**：用来接收 Actions，执行回调函数；
  - **Store（数据层）**：类似于 MV* 的 Model 层。用来存放应用的状态，一旦发生变动，就提醒 View 更新页面。
- **完整的数据流动过程**：用户通过与 view 交互或者外部产生一个 Action，Dispatcher 接收到 Action 并执行那些已经注册的回调，向所有 Store 分发 Action。通过注册的回调，Store 响应那些与它所保存的状态有关的 Action。然后 Store 会触发一个 change 事件，来提醒对应的 View 数据已经发生了改变。View 监听这些事件并重新从 Store 中获取数据。这些 View 调用它们自己的 `setState()` 方法，重新渲染自身及相关联的组件。
- **更多实例**：除了 Flux，当前 Web 前端比较常用的 **React** 也是比较典型的单向数据流框架，它也是基于 Redux 模型实现的。

**Q15: Redux 是什么？它的三大核心概念是什么？（12.2.1）**

- **定位**：Redux 作为 **Flux 模型一个友好简洁的实现**，它基于一个严格的单向数据流：应用中的所有数据都是通过组件在一个方向上流动。Redux 希望确保应用的视图是根据**确定的状态**来呈现的——即在任何阶段，应用的状态总是确定、有效的，并且可以转换到另一个可预测、有效的状态，视图将根据所处的状态来进行对应的展示。
- **1）Store**：保存应用的状态并提供方法来存取对应的状态，分发状态，并注册监听。
- **2）Actions**：与 Flux 类似。包含要传递给 Store 的信息，表明我们希望怎样改变应用的状态。比如，在 Kotlin 中我们可以定义如下 action：
  ```kotlin
  data class AddTodoAction(val title: String, val content: String)
  ```
  然后由 store 进行分发：
  ```kotlin
  store.dispatch(AddTodoAction("Finish your homeWork", "English And Math"))
  ```
- **3）Reducers**：Store 收到 Action 以后，必须给出一个新的 State，这样 View 才会发生变化。这种 State 的计算过程就叫作 Reducer：
  ```kotlin
  fun reduce(oldState: AppState, action: Action): AppState {
      return when (action) {
          is AddTodoAction -> {
              oldState.copy(todo = ...)
          }
          else -> oldState
      }
  }
  ```

**Q16: Flux 与 Redux 有何异同？**

- **相同点**：Redux 是 Flux 模型的实现，两者都是典型的单向数据流架构：数据只沿"Action → Store → View"一个方向流动，UI 变化不直接反向修改数据。
- **不同点（Redux 的简化）**：对比 Flux，我们可以发现一些不同点——Redux 作为 Flux 一个**友好而简洁**的实现，将"分发 + 存储 + 状态计算"的职责进一步收敛：由单一的 Store 统一负责存取状态、分发状态与注册监听，Actions 只负责用**陈述性**的信息描述期望的状态变更，而状态的计算完全交给 **Reducer 纯函数**完成（给定相同的输入 State 与 Action，永远返回相同的输出 State）。
- **设计目标**：通过这种简化，Redux 确保了"视图根据确定状态呈现"的可预测性：任何阶段应用的状态都是确定、有效、可预测地转换的。

**Q17: 单向数据流最大的优势是什么？为什么它的数据追溯能力更强？（12.2.2）**

- **总述**：单向数据流架构的最大优势在于整个应用中的数据流以**单向流动**的方式，从而使得拥有**更好的可预测性与可控性**，这样可以保证应用各个模块之间的**松耦合性**。
- **对比 MVVM 的"自动同步"困境**：在 MVVM 中，数据变动时由框架自动帮我们实现视图的同步变更，更改一个地方的数据，可能会影响很多地方的状态，并且它是**不可预期**的，很难维护和调试。而单向数据流的架构中，整个应用状态是**可预测**的，我们可以监听到数据变动，从而采取自定义的操作。
- **单一数据入口**：对于一个组件来说，数据入口只有唯一一个。当数据发生改变时，UI 也会发生改变；反之 UI 的变化并不会直接变动数据。这不仅使得程序更直观、更容易理解，而且更有利于应用的可维护性。

**Q18: 为什么单向数据流能带来更简洁的单元测试？**

- **可以"伪造"事件**：因为 Dispatcher 是所有 Action 的处理中心，即使没有对应的事件发生，我们也可以"伪造"一个出来，只需要用 Action 对象向 Dispatcher 描述当前的事件，就可以执行对应的逻辑。
- **Reducer 是纯函数（核心原因）**：在 Redux 中，由于 Reducer 是纯函数而没有内部状态，对于给定的输入状态和操作，它们将始终返回相同的输出状态。因为 State 和 Action 相对是轻量级的，所以我们可以把测试重点放在 Reducer 上。在 Kotlin 中代码可能是这样的：
  ```kotlin
  class TodoReducer {
      fun reduce(state: AppState, action: Action): AppState {
          // todo 逻辑操作
      }
  }

  data class TodoAction(val text: String)

  val todoReducer = TodoReducer()
  val originalState = AppState(/* todo 初始状态 */)
  val todoAction = TodoAction(text = "just haha")
  val newState = todoReducer.reduce(originalState, todoAction)
  // 判断newState与预期是否一致
  assert(newState ...)
  ```
- **多来源数据的测试技巧**：如果数据需要从多个地方获取（比如，本地存储或网络中获取），我们可以改变 Action 的结构：
  ```kotlin
  class TodoAction(val dataOfLocal: String, val dataOfApi: String) {
      companion object {
          fun create(localStore: LocalStore, apiResponse: ApiResponse): TodoAction {
              val dataOfLocal = localStore.targetData
              val dataOfApi = apiResponse.targetData
              return TodoAction(dataOfLocal = dataOfLocal, dataOfApi = dataOfApi)
          }
      }
  }
  ```
  这样测试起来也是很容易：
  ```kotlin
  val todoAction = TodoAction(dataOfLocal = "i'm from sqlite", dataOfApi = "i'm from api")
  val newState = reducer.reduce(originalState, todoAction)
  ......
  ```

**Q19: 单向数据流遇上 Kotlin 后有什么优势？（Kotlin 如何拯救样板代码）**

- **背景**：因为 Redux 是基于 Flux 的思想产生的，所以在 Redux 架构中构造组件，通常也会产生许多样板代码。对于 JavaScript 来说，这可能难以优化。而使用 Kotlin，我们能更加方便地管理样板代码。
- **Java 的套路**：当我们在 Reducer 中匹配不同类型的 Action 时，按照 Java 的套路可能会这样写——要么在 Action 上添加 `type` 字段做 switch 分支：
  ```java
  AppState reduce(Action action, AppState oldState) {
      switch (action.type) {
          case TodoAction.TYPE.ADD_TODO_ITEM:
              return addTodoAction(oldState, action);
          case TodoAction.TYPE.CHANGE_STATE:
              return changeAction(oldState, action);
          default:
              return oldState;
      }
  }
  ```
  要么当 Action 结构相对比较复杂、不想再添加一个 type 字段时，直接判断 Action 属于什么类：
  ```java
  AppState reduce(Action action, AppState oldState) {
      if (action instanceof AddTodoAction) {
          return addTodoAction(oldState, action);
      } else if (action instanceof ChangeTodoAction) {
          return changeAction(oldState, action);
      } else if (...) {
          ......
      }
      return oldState;
  }
  ```
  这个时候，如果 action 非常多，就会给开发者带来巨大的痛苦。
- **Kotlin 的 when 来拯救**：
  ```kotlin
  fun reduce(action: Action, oldState: AppState): AppState {
      return when (action) {
          is AddTodoAction -> reduceAddTodoAction(oldState, action)
          is RemoveTodoAction -> reduceRemoveTodoAction(oldState, action)
          else -> oldState
      }
  }
  ```
- **Smart Casts 的额外好处**：我们还能利用 **Smart Casts**，在数据处理的同时避免不必要的判断。当然，这里只是用 Kotlin 提升 Redux 架构便捷性的冰山一角。
- **总结**：虽然 Redux 起源于 Web 端，但从它的构建中，我们可以看到很多非常好的想法，这都是值得学习并可以尝试引入 Android 的。即使我们的平台、语言和工具可能不同，但在架构层面，我们面对着许多相同的基本问题，比如，尽可能降低 View 和业务逻辑代码的耦合度等。

**Q20: ReKotlin 是什么？它奉行哪些核心设计？（12.3.1）**

- **背景**：如果你是一名 Android 开发者，你应该知道：在国内的项目中，鲜有单向数据流架构的痕迹。甚至一些经验不够丰富的 Android 开发者，可能都不知道"单向数据流"。
- **渊源**：在 iOS 中，有一个著名的单向数据流框架 **ReSwift**，它在 GitHub 上的被关注度还不错。随着 Kotlin 在 Android 中的地位不断提高，利用其优秀的语言特性，也派生出了类似的框架：**ReKotlin**。它的出现，也宣布了 Android 即将"跨入单向数据流时代"。
- **基于经典 Redux 模型，ReKotlin 奉行的设计**（三大核心概念见 Q15）：
  - **The Store**：以**单一数据结构**管理整个 App 的状态，状态只能通过 dispatch Actions 来修改；状态改变时通知所有 Observers。
  - **Actions**：以陈述形式描述一次状态变更，**不包含任何代码**，由 Store 转发给 Reducers。
  - **Reducers**：基于当前 Action 和 App 状态，通过**纯函数**返回新的 App 状态。
- **对单向数据流的直观概括**：单向数据流意味着应用程序的 **State 不应该保存在许多不同的地方**。相反，存储组件将所有 State 保持在**中心位置**。View 会对 State 的更改做出反应，而不是在内部处理它。Action 是触发 State 更改的唯一方法，它不会通过它们自己来更改状态，而更像是一些**指令**——表示某些内容将发生变化。这些"指令"是针对使用执行实际状态更改的 Reducers 的 Store 对象发出的。
- **Middleware（中间件）**：由于 Action 的接收方 Reducer 都是纯函数、不能产生副作用，因此引入了中间件，它主要用来处理**副作用**（如网络请求、日志打印、数据库操作等），这会在后面介绍。

**Q21: 如何创建基于 ReKotlin 的项目？（引入依赖与整体结构）（12.3.2）**

- **1. 引入 ReKotlin**：在 Gradle 中集成 ReKotlin，这里以 1.0.0 版本为例，同时加上一些日常需要的框架（版本仅供参考，引入实际项目时可酌情调整）：
  ```gradle
  dependencies {
      implementation 'com.android.support:recyclerview-v7:27.1.1'
      implementation 'com.android.support:cardview-v7:27.1.1'
      implementation 'com.android.support:design:27.1.1'
      // reKotlin
      implementation "org.rekotlin:rekotlin:1.0.0"
      // http
      implementation 'com.squareup.retrofit2:retrofit:2.3.0'
      implementation 'com.squareup.retrofit2:converter-gson:2.3.0'
      // imageLoader
      implementation 'com.squareup.picasso:picasso:2.5.2'
      // json
      implementation 'com.google.code.gson:gson:2.8.2'
      // log
      implementation 'com.jakewharton.timber:timber:4.6.0'
  }
  ```
- **2. 整体结构**：本次的示例主要是做一个电影列表，从开源 API 中获取数据，然后将其显示到 App 中。项目文件清单如下：
  ```
  - actions
    - MovieListActions.kt
  - middlewares
    - MovieMiddleWare.kt
    - NetworkMiddleWare.kt
  - model
    - Movie.kt
  - network
    - Api.kt
    - HttpClient.kt
  - reducers
    - AppReducer.kt
    - MovieListReducer.kt
  - states
    - AppState.kt
    - MovieListState.kt
  - ui
    - BaseActivity.kt
    - MovieDetailActivity.kt
    - MovieListAdapter.kt
    - MovieListFragment.kt
    - MainActivity.kt
  - utils
    - ImageLoder.kt
    - Logger.kt
  - MovieApplication.kt
  ```
- **新增目录的职责（为什么这样分层）**：其中 model、network、ui、utils 文件夹与我们平常的项目结构类似。把目光聚焦在新增加的 actions、middlewares、reducers、states 目录上：
  - **actions**：所有更新 State 的行为我们都可以抽象成 Action，并且根据不同的场景分布在不同文件下；
  - **reducers**：不同 Action 对应的响应中心，会返回一个新的状态（State）；
  - **middlewares**：由于 Action 的接收方 Reducer 都是纯函数，不会也不能产生副作用，如果我们想加入一些额外的操作，例如打印日志、操作 SQLite 数据库等，我们可以将这些操作放到该文件夹中；
  - **states**：所有状态的声明都放在这个目录下。

**Q22: ReKotlin 中 Store 是如何初始化的？（MovieApplication）**

- **为什么在 Application 中初始化**：在 ReKotlin 中，**每个 App 对应只有一个数据管理中心（Store）**。所以，我们可以在 Application 中将其初始化，项目中我们使用的自定义的 Application 名为 MovieApplication：
  ```kotlin
  import android.app.Application
  import dripower.rekotlinsimpleexample.middlewares.movieMiddleWare
  import dripower.rekotlinsimpleexample.middlewares.networkMiddleWare
  import dripower.rekotlinsimpleexample.ruducer.appReducer
  import org.rekotlin.Store

  val store = Store(
      reducer = ::appReducer,
      state = null
  )

  class MovieApplication : Application() {
      ...
  }
  ```
- **顶层 val 的意义**：通过顶层的 `store` 全局单例，任何 View 都可以拿到这唯一的数据源，从而保证"所有 State 保持在中心位置"这一单向数据流的核心理念。

**Q23: ReKotlin 中 View 是如何与 Store 数据流绑定的？（MovieListFragment）**

- **View 部分**：示例采用 Activity + Fragment 的常规组合。先看 BaseActivity 与 MainActivity——MainActivity 通过一个事务扩展函数渲染出 MovieListFragment：
  ```kotlin
  import android.support.v4.app.FragmentTransaction
  import android.support.v7.app.AppCompatActivity

  abstract class BaseActivity : AppCompatActivity() {

      inline fun BaseActivity.transFragment(action: FragmentTransaction.() -> Unit) {
          supportFragmentManager.beginTransaction().apply {
              action()
          }.commit()
      }
  }
  ```
  ```kotlin
  import android.os.Bundle
  import android.support.v4.app.Fragment
  import dripower.rekotlinsimpleexample.R
  import dripower.rekotlinsimpleexample.ui.movieList.MovieListFragment

  class MainActivity : BaseActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_main)
          showFragment(MovieListFragment())
      }
      private fun showFragment(fragment: Fragment) {
          transFragment {
              replace(R.id.container, fragment)
          }
      }
  }
  ```
- **MovieListFragment：View 与 Store 绑定的重点**。通常，在与数据打交道的界面中，我们都会实现 `StoreSubscriber<TState>` 接口（这是 ReKotlin 中实现的，我们可以直接使用）：
  ```kotlin
  class MovieListFragment : Fragment(), StoreSubscriber<MovieListState?> {
      private lateinit var movieListAdapter: MovieListAdapter

      override fun newState(state: MovieListState?) {
          state?.movieObjects?.let {
              initializeAdapter(it)
          }
      }

      override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                savedInstanceState: Bundle?): View? =
          inflater.inflate(R.layout.fragment_movie_list, container, false)

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)
          store.dispatch(LoadTop250MovieList())
      }

      ......

      override fun onStart() {
          super.onStart()
          store.subscribe(this) {
              it.select {
                  it.movieListState
              }.skipRepeats()
          }
      }

      override fun onStop() {
          super.onStop()
          store.unsubscribe(this)
      }
  }
  ```
- **各个生命周期中要做的事情（为什么这样分配）**：
  1. **`override fun newState(state: MovieListState?)`**：这里相当于一个**数据流管道的出口**，当数据 state 变化时，我们可以对其做一些相应的操作，有点类似前端中的 Watch 机制。示例中，每次数据变化我们都更新列表数据，重新渲染；
  2. **onViewCreated**：通常我们在这个生命周期里**发起数据请求**（网络或者本地数据库）。示例中，我们仅做了网络请求；
  3. **onStart**：我们通常在这里进行 **store 的绑定**（subscribe）；
  4. **onStop**：相应地，我们需要在视图不显示的时候，**解除 store 绑定**，防止内存泄漏等问题。
- **列表与详情页**：其余代码为列表适配器绑定以及跳转 MovieDetailActivity。MovieListAdapter 只是常规的 RecyclerView.Adapter（绑定评分、标题、图片，并处理图片点击跳转详情）；MovieDetailActivity 界面很简单：包含一个返回按钮，两个文本框分别显示传入的 movie name 及 id：
  ```kotlin
  class MovieDetailActivity : BaseActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_movie_detail)
          val id = intent.extras?.get("id")
          val title = intent.extras?.get("title")
          tv_movie_id.text = id as String
          tv_movie_title.text = title as String
          btn_back.setOnClickListener { this.finish() }
      }
  }
  ```

**Q24: ReKotlin 中 State、Action、Reducer 是如何定义的？**

- **先从状态（State）开始**：既然称为单向数据流，我们最核心的地方肯定都是围绕数据展开的。对于某个场景来说，我们的数据为该场景的**状态（State）**服务，而状态直接决定了该场景视图显示的内容。所以我们需要先确定好这些状态。对于当前示例，我们需要显示 movieList：
  ```kotlin
  import dripower.rekotlinsimpleexample.model.Subject
  import org.rekotlin.StateType

  data class MovieListState(
      var movieObjects: List<Subject>? = null
  ) : StateType
  ```
- **统一管理多个场景的状态**：一个 App 中会对应很多场景，我们同样需要进行统一管理。当前示例中我们将其放入 AppState：
  ```kotlin
  data class AppState(
      var movieListState: MovieListState? = null
  ) : StateType
  ```
- **定义动作（Action）**：确定最终显示效果之后，我们可以打开数据流的开关，并且控制它们的流向，为当前场景的操作定义不同的动作（Action）：
  ```kotlin
  class InitMovieList(val movieData: List<Subject>) : Action
  class LoadTop250MovieList : Action
  class ShowMovieList(val movieData: List<Subject>) : Action
  ```
  我们将所有对 MovieList 的操作都放入 Action 中，以方便管理。当我们调用 `store.dispatch(Action())` 之后，才能让 Reducer 处理 State 的变化。
- **编写 Reducer**：AppReducer 负责组合分发到各个场景的 reducer；MovieListReducer 针对当前场景，对 ShowMovieList 作出响应并产生新状态：
  ```kotlin
  fun appReducer(action: Action, appState: AppState?): AppState =
      AppState(movieListState = movieListReducer(action, appState?.movieListState))
  ```
  ```kotlin
  fun movieListReducer(action: Action, movieListState: MovieListState?): MovieListState {
      var state = movieListState ?: MovieListState()
      when (action) {
          is ShowMovieList -> {
              state = state.copy(movieObjects = action.movieData)
          }
      }
      return state
  }
  ```
- **完整闭环**：View 派发 Action → Store 交给 Reducer 计算新 State → 状态变化触发订阅者的 `newState()` → 重新渲染列表。这样，一个单向数据流架构的 App 就完成了。

**Q25: ReKotlin 中的 Middleware 中间件起什么作用？如何实现？（处理副作用）**

- **为什么要中间件**：我们知道，Reducer 是一个**没有副作用**的处理，所以如果需要对数据进行中间加工或者打印日志等，都需要放到**中间件 Middleware** 中。
- **如何接入**：如果使用 Middleware，需要在初始化 Store 的时候传入 Middleware 参数。本示例中我们在 MovieApplication 中初始化 Store，需要做如下更改：
  ```kotlin
  val store = Store(
      ......
      middleware = listOf(networkMiddleware, movieMiddleware)
  )
  ```
- **NetworkMiddleware：把网络请求当作副作用**。本示例中，我们将网络请求获取 MovieList 的逻辑放在 networkMiddleware 中，当返回正确的结果时，我们进行渲染 movieList 的操作，否则打印错误日志：
  ```kotlin
  internal val networkMiddleware: Middleware<AppState> = { dispatch, _ ->
      { next ->
          { action ->
              when (action) {
                  is LoadTop250MovieList -> {
                      getTop250MovieList(dispatch)
                  }
              }
              next(action)
          }
      }
  }

  // 这里即获取movie数据的核心逻辑
  private fun getTop250MovieList(dispatch: DispatchFunction) {
      val apiService = HttpClient.client?.create(Api::class.java)
      val call = apiService?.getTop250MovieList()
      call?.enqueue(object : Callback<MovieResponse> {
          override fun onFailure(call: Call<MovieResponse>?, t: Throwable?) {
              Logger.error(t)
          }
          override fun onResponse(call: Call<MovieResponse>?, response: Response<MovieResponse>?) {
              val movieObjects = response?.body()?.subjects
              movieObjects?.let {
                  dispatch(InitMovieList(it))
              }
          }
      })
  }
  ```
- **MovieMiddleware：加工后再次派发**。同时，我们在初始化 MovieList 的时候（即发送 InitMovieListAction），让 Action 进入 Middleware 中，在这里可以对 movieList 做一些有副作用的操作，加工完毕后派发新的 Action（ShowMovieList）回到数据流：
  ```kotlin
  internal val movieMiddleware: Middleware<AppState> = { dispatch, _ ->
      { next ->
          { action ->
              when (action) {
                  is InitMovieList -> {
                      processMovies(action.movieData, dispatch)
                  }
              }
              next(action)
          }
      }
  }

  private fun processMovies(movieObjects: List<Subject>, dispatch: DispatchFunction) {
      // 你可以在这里对movieList进行一些有副作用的操作，例如：打印日志、操作SQLite数据库等
      dispatch(ShowMovieList(movieObjects))
  }
  ```
- **最终联动**：当 MovieListReducer 接收到 ShowMovieList 的 action 时，将会更新 state 中的 movieObjects。还记得我们在 MovieListFragment 中实现了 `StoreSubscriber<MovieListState?>` 接口吗？当 MovieListState 发生变化时，将会触发 `newState(state: MovieListState?)` 方法，这样就会重新渲染 movieList。如果你的代码正确，此时一个完整的列表界面就呈现出来了。
- **局限与期望**：当然，这样还不是最理想的使用方式。在单元测试的时候，可能依旧局限于单个视图下的数据操作，即只能保证数据流的验证（虽然在单向数据流中，这已经足够验证我们的视图正确显示了——除非你的 UI 显示逻辑显示错误）。要是我们能够对视图进行测试，那该多好啊！

**Q26: 传统视图导航存在哪些问题？（12.4.1）**

- **背景**：经过以上介绍，相信你已经能够掌控数据流了。现在要解决另一个问题：如何解耦视图导航。在移动端，我们需要借助视图导航来完成页面切换及数据传递。随着 App 的业务不断复杂化，传统的视图导航存在着许多不便之处。
- **1. 高耦合的 Activity.class**：在传统的 Android 开发中，显示跳转 Activity 我们一般这样写：
  ```kotlin
  val intent = Intent()
  intent.setClass(this, TargetActivity::class.java)
  startActivity(intent)
  ```
  这是绝大多数 Android 开发者的首选做法。所以这看上去非常和谐，不存在任何问题。但是实际上造成了**很高的耦合性**：当前 Activity 如果要跳转到 TargetActivity，就一定要引用 TargetActivity。这衍生出了两个问题：
  - 如果项目中存在多个 Module，底层 Module 中 Activity **不能跳转到**上层的 Activity；
  - 如果 TargetActivity **类名变化**，调用的地方需要相应改动。
- **2. 难以管理的 intent-filter**：在 Android 中，我们通常用 intent-filter 来隐式启动/跳转 Activity：
  ```kotlin
  val intent = Intent()
  intent.action = Intent.ACTION_SENDTO
  intent.data = Uri.parse("smsto:10000")
  context.startActivity(intent)
  ```
  如果项目中存在多个 Module、Activity，需要在各自 Module 的 AndroidManifest.xml 中声明配置，**容易重复，难以统一管理**。
- **3. 不友好的 Hybrid**：在 React Native、Weex、Flutter 大行其道的现实环境下，我们难免会与混合开发打交道。当 H5 页面需要跳转到 Native，并且需要把相关数据传递过去时，通常情况下，我们会采取两种做法：
  - 直接根据目标 Activity 的 Action 中的 Scheme 跳过去；
  - Native 维护一个 `<关键字，Activity>` 的 Map，H5 传过来 Activity 的"关键字"，Native 在 Map 中查到后进行跳转。
  - **第 1 种情况**：Action 命名需要符合 iOS 和 Android 两个平台的规范，如果当前版本的 Native 不支持该 Action，还需要进行跳转失败的处理；
  - **第 2 种情况**：维护 `<关键字，Activity>` 的 Map 比较麻烦。另外，Activity 的存储及生命周期的处理都会存在问题；
  - 并且在两种情况下，我们都可能难以获取到 Context 的引用，这时候需要使用 **Application 的 Context**。

**Q27: rekotlin-router 是什么？如何用它实现声明式路由与导航解耦？（12.4.2）**

- **背景**：以上几种都是传统导航中存在的问题。作为国内开发者，我们应该接触过著名的开源框架 **ARouter**，它能够给以上问题一个很好的解决方案，并且还能解决其他额外的很多问题。但是对于我们来说，这也许有些"重"，我们可以使用与 ReKotlin 配套的 **rekotlin-router**。
- **定位**：ReKotlin 的主要贡献者对 rekotlin-router 是这么阐述的：ReKotlin 的**声明式路由**，允许开发者以 Web 上使用 URL 类似的方式声明路由。
- **引入依赖**：
  ```gradle
  implementation 'org.rekotlinrouter:rekotlin-router:0.1.9'
  ```
- **扩展导航状态**：将原有 AppState 扩展出导航的状态：
  ```kotlin
  import org.rekotlinrouter.HasNavigationState
  import org.rekotlinrouter.NavigationState

  data class AppState(
      ...
      override var navigationState: NavigationState
  ) : StateType, HasNavigationState
  ```
- **创建 Router 实例**：在初始化 AppState 之后，我们需要创建 Router 的实例。需要传入关联的 store 与根 Routable：
  ```kotlin
  router = Router(
      store = mainStore,
      rootRoutable = RootRoutable(context = applicationContext),
      stateTransform = { subscription ->
          subscription.select { stateType ->
              stateType.navigationState
          }
      }
  )
  ```
- **封装跳转 Route 的 Action**：然后我们封装一个跳转 Route 的 Action。它实现了 `StandardActionConvertible`，可以将 Kotlin 的 typed action 与路由框架的 `StandardAction`（类似 URL 的 payload 描述）互相转换：
  ```kotlin
  import org.rekotlin.Action
  import org.rekotlinrouter.Route
  import org.rekotlinrouter.StandardAction
  import org.rekotlinrouter.StandardActionConvertible

  class SetRouteAction(
      private var route: Route,
      private var animated: Boolean = true,
      action: StandardAction? = null
  ) : StandardActionConvertible {
      companion object {
          const val type = "RE_KOTLIN_ROUTER_SET_ROUTE"
      }
      init {
          if (action != null) {
              route = action.payload?.keys?.toTypedArray() as Route
              animated = action.payload!!["animated"] as Boolean
          }
      }
      override fun toStandardAction(): StandardAction {
          val payloadMap: HashMap<String, Any> = HashMap()
          payloadMap.put("route", this.route)
          payloadMap.put("animated", this.animated)
          return StandardAction(
              type = type,
              payload = payloadMap,
              isTypedAction = true
          )
      }
  }

  class SetRouteSpecificData(val route: Route, val data: Any) : Action
  ```
- **调用方式（解耦效果）**：综上，我们就可以这样调用——用声明式的 Route 数组代替对目标 Activity 类的直接引用：
  ```kotlin
  private fun movieListToDetailRoute() {
      val routes = arrayListOf(Routers.mainActivityRoute, Routers.movieDetailActivityRoute)
      val action = SetRouteAction(route = routes)
      store.dispatch(action)
  }
  ```
- **为什么更优雅**：这样是否比之前优雅了很多？就算在复杂的项目中，我们也能很好地管理页面跳转——导航变成了数据流中的一个 Action，路由表集中管理，页面之间不再直接引用彼此的 Class，彻底解耦了视图导航。

**Q28: 本章小结：MV* 家族与单向数据流、ReKotlin 各解决了什么问题？（12.5）**

- **（1）主流的客户端架构**：目前比较主流的客户端架构即 MV* 家族：MVC、MVP、MVVM。其中 **MVC 适合小而简单的 App**，而 **MVP 和 MVVM 的选择需从 App 具体业务场景出发**。从 MVC 到 MVP 的演变**完成了 View 与 Model 的解耦**，改进了职责分配与可测试性。而从 MVP 到 MVVM，添加了 **View 与 ViewModel 之间的数据绑定**，使得 View 完全无状态化。
- **（2）从 MV* 到单向数据流**：单向数据流在前端页面中是一种非常流行的架构方式，在 React 和 Vue 中其优点得到极致的体现。从 MV* 到单向数据流的变迁采用了**消息队列式的数据流驱动**的架构，并且以 **Redux** 为代表的方案将原本 MV* 中**碎片化的状态管理变为统一的状态管理**，保证了状态的**有序性与可回溯性**。
- **（3）ReKotlin**：Kotlin 的崛起，让 Android 能够顺滑地支持 **ReSwift 的思想**。利用 ReKotlin，我们可以在 Android 中更容易地实现单向数据流架构，在强有力的状态的有序性与可回溯性前提下，我们能够提供比 MV* 架构**更详尽的单元测试**。并且在复杂的业务场景下，也不易出现难以排查的问题和冗杂的代码。

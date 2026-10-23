# C++ 文件流

> 本文说明文件输入流 ifstream 的类型结构、打开模式、流状态、格式化与非格式化读取、随机访问和生命周期管理，并覆盖 ofstream 与 fstream 参与的打开模式组合。标注"实测"的行为在本机 g++ 11.4.0（libstdc++，x86-64，Ubuntu 22.04，-std=c++17）验证，实现相关的行为数值不可移植。

**Q1: [learning] ifstream 解析：ifstream 是什么，与 ofstream 和 fstream 是什么关系？**

ifstream 是 C++ 标准库的输入文件流类，把磁盘文件关联到流的输入接口上，按格式化（>>、getline）或非格式化（read、get）方式读取数据。它与 ofstream（只写）、fstream（可读可写）同属 fstream 家族：三个类都是"流格式化层加同一个文件缓冲类"的组合，继承自通用流基类，因此与 cin 共享同一套输入接口。

1. **继承位置：**basic_ifstream 从 basic_istream 派生，basic_ofstream 从 basic_ostream 派生，basic_fstream 从同时继承两者的 basic_iostream 派生。因此 >>、getline、get、read、seekg 这些输入接口对 ifstream 全部可用，形参为 std::istream& 的函数可以直接传 ifstream，与传 cin 没有区别。
2. **流与文件的分层：**三个类内部各自持有一个 basic_filebuf 文件缓冲对象，真正 open、close 文件和维护缓冲区的是 filebuf，流类负责格式化解析和状态管理。
3. **类型别名：**ifstream 是 basic_ifstream<char> 的别名，面向 char。宽字符文件流是 wifstream。
4. **与 FILE* 的分工：**C 标准库的 fopen 返回 FILE* 句柄，打开、关闭和缓冲全靠手动配对。ifstream 是 RAII（Resource Acquisition Is Initialization）对象，构造打开、析构关闭，缓冲由 filebuf 托管。

```cpp
#include <fstream>
#include <iostream>

// 对通用输入流编写的函数，cin 和 ifstream 都能传入
int ReadPort(std::istream& in) {
    int port = 0;
    in >> port;
    return port;
}

int main() {
    std::ifstream file("config.txt");   // 构造即打开，默认模式 in
    if (!file) {
        return 1;                       // 打开失败
    }
    int port = ReadPort(file);          // ifstream 就是 istream
}
```

程序从 config.txt 读出首个整数。把 file 换成 std::cin 时同一函数照常工作——两个对象走的是同一条 istream 接口。

**Q2: [learning] 打开文件有哪些写法，六个打开模式各自控制什么？**

打开文件有两条等价路径：构造时传入文件名，或先构造再调用 open() 成员。打开行为由 ios 基类的六个枚举标志组合决定，ifstream 的默认模式是 in。

1. **in：**允许从文件读。ifstream 的模式参数默认就是 in，且标准规定 ifstream 打开时强制附加 in——实测给 ifstream 只传 out，打开后仍能读，文件也没有被截断。
2. **out：**允许向文件写。ofstream 默认含 out，同样被强制附加，因此 ofstream 永远可写。
3. **app：**append（追加）模式，每次写操作前都先把写入位置移到文件尾。实测 app 模式下先 seekp(0) 再写入，字节仍落在文件尾，seekp 对写位置的控制失效。
4. **ate：**at end，仅在打开完成后立刻把位置移到文件尾一次，之后的读写位置照常自由移动。实测 in|ate 打开后 tellg 等于文件大小，seekg(0) 后照常从头读。
5. **trunc：**truncate（截断），打开成功后把文件内容清空。省略 trunc 不代表一定保留内容——单独的 out 打开同样会截断，是否截断取决于标志组合。
6. **binary：**按字节原样读写。省略时为文本模式，Windows 上写入的 \n 会转成 \r\n，读取时反向转换。Linux/POSIX（Portable Operating System Interface）不做行尾转换，两种模式行为一致。

```cpp
std::ifstream a("config.txt");                    // 构造即打开，模式缺省为 in
std::ifstream b;
b.open("config.txt", std::ios::in | std::ios::binary);   // 先构造再打开，显式给模式
```

两条路径最终都调用同一个 filebuf 的 open。模式参数缺省与否只影响传给底层 open 的标志组合。

**Q3: [learning] 打开模式的不同组合会带来哪些截断与创建差异？**

模式组合决定打开后内容是否保留、文件是否新建。核心变量有两个：截断由写方向的打开语义决定——单独的 out 打开就会截断，不需要显式 trunc。文件是否新建由 trunc 或 app 决定。以下行为在 libstdc++ 上逐一实测：

1. **out（ofstream 默认）：**已有内容被截断。实测内容 AAAAA 的文件以 ofstream 打开写入 B 后只剩 B。
2. **out|ate：**仍先截断再定位，实测打开后 tellp 为 0，原内容不保留。ate 只改变"打开后定位到尾"这一步，挡不住 out 自带的截断。
3. **in|out：**不截断且可读可写，但文件不存在时打开失败（is_open 为 0）。适合读改写已有文件。
4. **in|out|trunc：**截断已有内容，文件不存在则创建。
5. **app：**不截断，文件不存在则创建，写入只追加。
6. **in（ifstream 默认）：**不截断，文件不存在则打开失败。

这些结果来自 libstdc++ 把模式映射到 C 标准库 fopen 模式串的实现：in 对应 r、out 对应 w、app 对应 a、in|out 对应 r+、in|out|trunc 对应 w+，截断与创建行为随映射继承。标准要求各实现提供等价的打开语义，但把 out 直接映射为截断的 w 是主流实现的共同选择，不能假设单 out 不截断。

**Q4: [learning] 如何判断 ifstream 打开文件成功，失败后流处于什么状态？**

打开失败默认不抛异常，失败体现在两处：is_open() 返回 false，流状态置 failbit。惯用的 if (!file) 把两者一并覆盖，打开后应立即检查。

1. **失败痕迹（实测）：**打开不存在的文件后 is_open() 为 0、fail() 为 1、good() 为 0，rdstate() 只含 failbit。此后所有读取操作不消费任何数据直接返回失败。
2. **两个接口的分工：**is_open() 只回答"流是否还关联着文件"。fail() 检查状态位，打开成功后解析出错或读到文件尾同样会置 failbit，此时 is_open() 仍为 true。两个问题互相独立，不能互相替代。
3. **推荐写法：**

    ```cpp
    std::ifstream file("config.txt");
    if (!file) {
        // 打开失败：is_open() 为 false 且 failbit 已置位
        return 1;
    }
    ```

需要区分"文件没打开"和"打开成功但读取失败"时，才分别查询 is_open() 与 rdstate()。日常只判断流对象本身的真假即可。

**Q5: [learning] 用 >> 提取数据时按什么规则解析，失败后为什么会持续失败？**

>> 是格式化提取：先跳过空白，按目标类型的解析规则读取字符，停在第一个不符合的字符前。失败会置 failbit，而流状态是黏住的——后续提取不消费任何数据直接失败，必须 clear() 才能继续。

1. **解析成功的停留位置（实测）：**对 123abc 提取 int 得到 123，peek() 返回 a：整数解析在第一个非数字字符前停下，该字符留在流中，后续读取从它继续。
2. **有字符但解析失败（实测）：**对 abc 提取 int：failbit 置位，目标被置为 0（C++11 起，此前版本目标保持原值），a、b、c 一个字符都没消费。clear() 后改用 >> string 能原样读出 abc。
3. **无字符可提取（实测）：**流中只剩空白时提取 int，跳过空白后直接遇到文件尾：failbit 和 eofbit 置位，但目标保持原值——没有任何字符参与解析，不存在"置 0"一说。
4. **提取 string：**以空白定界，从第一个非空白字符读到下一个空白之前，string 按需扩容没有长度上限。\n 属于空白，因此 >> 永远不会把行尾换行符读进 string。
5. **skipws：**默认置位，提取前跳过空格、制表符和换行。用操纵符 noskipws 可关闭跳过行为，此后空白字符也会成为提取对象。

**Q6: [learning] 成员 getline、std::getline 与 >> 读文件时有什么行为差异？**

三种读法的关键差异集中在定界符去向和长行处理：>> 把换行符留在流中，成员 getline(char*, n) 消费换行符但有 n-1 字符上限，std::getline(流, string) 消费换行符且自动扩容。读整行默认选 std::getline(流, string)。

1. **>> 留下换行符：**>> 以空白定界，读完一项后行尾的 \n 留在流中，紧接着的 getline 会立刻读到空行。混用两种读法时，先用 >> 再按行读要把残留的换行符先消费掉。
2. **成员 getline 的截断边界（实测）：**char buf[8] 读 13 字符的行：提取 7 字符（上限 n-1）后缓冲已满，failbit 置位，gcount() 为 7，buf 以空字符收尾。不清状态的话后续 getline 全部无效。clear() 后再次 getline 会从行内剩余的 789XYZ 继续——一行被截断点拆成多段读，failbit 标记的就是"缓冲装满"这一事件。
3. **std::getline(string)（实测）：**同一行一次读入 13 字符，string 自动扩容没有截断问题。定界符被消费但不进入 string，最后一行没有换行符时也能正常读出内容。
4. **区分空行与文件尾：**读到空行只消费一个 \n，返回的 string 为空但流仍为 good。流已在文件尾时 getline 返回空 string 且置 failbit 加 eofbit。判断"是否读完"要看流状态，不能只看 string 是否为空。
5. **gcount() 的口径：**返回上一次非格式化输入操作（get、getline、read、ignore 等）实际提取的字符数，含被丢弃的定界符。

**Q7: [learning] failbit、eofbit、badbit 分别表示什么，检查和清除状态的接口有哪些？**

流状态是三个独立状态位的组合，类型为 iostate：eofbit 表示操作中触及文件尾，failbit 表示上一次操作失败但流可恢复，badbit 表示流已损坏不可恢复。eofbit 可以与成功提取共存，它标记的是"过程触及了文件尾"，不是"操作失败"。

1. **eofbit：**读取过程中触及文件尾就置位，哪怕本次提取本身成功。实测对 10 20 30 连续提取三个 int 后，fail() 为 0 而 eof() 已为 1。
2. **failbit：**操作失败的通用标记：类型解析失败、打开失败、缓冲截断、read 不足都置它。清掉后流可以继续使用，读取位置停在失败处。
3. **badbit：**底层 I/O 出错等不可恢复的损坏，badbit 置位后通常放弃这个流。

围绕这些状态位有五个接口，按用途区分：

1. **good()：**无任何状态位置位时为 true。
2. **fail()：**failbit 或 badbit 任一置位即为 true，因此流对象的 operator bool 等价于 !fail()。
3. **eof() 与 bad()：**分别只检查 eofbit 与 badbit。
4. **rdstate() 与 setstate()：**rdstate() 返回原始位组合。setstate() 在现有状态上追加置位。
5. **clear()：**无参调用把三个位全部清零，也可传入 iostate 指定状态。它不动缓冲、不移动读写位置。流的异常掩码默认为 goodbit，任何位置位都不抛异常，需要"状态变化即抛出"时用 exceptions() 显式指定。

**Q8: [learning] while (file >> x) 为什么能作循环条件，结束后如何区分读完与中途出错？**

循环条件用的是流到 bool 的显式转换，等价于 !fail()：提取成功循环继续，置了 failbit 循环结束。退出时流必处于失败状态，区分"正常读完"与"中途解析出错"只看 eof()——读完的终态是 failbit 加 eofbit，中途出错的终态只有 failbit。

1. **正常读完（实测）：**对 10 20 30 循环提取，读入 3 个值后第 4 次提取无字符可读，rdstate 变为 failbit 加 eofbit，eof() 为 true。
2. **中途出错（实测）：**对 10 xx 30 循环提取，读入 1 个值后在 xx 处解析失败，rdstate 只含 failbit，eof() 为 false。流停在出错字符处，clear() 后可以报告出错位置并继续处理。
3. **判定规则：**循环结束后 file.eof() 为 true 即正常读完。eof() 为 false 而循环退出，说明存在格式错误，可结合 bad() 排除底层损坏。
4. **不要用 good() 预判循环（实测）：**文件为 10 20 30 且末尾有空格时，读完 30 后流停在空格处、eofbit 未置位，good() 为 true，循环多进一轮：这一轮提取无字符可读而失败，x 保持旧值 30，最后一项被处理两遍。循环条件直接写流本身，eof 的判定放在循环结束后。

**Q9: [learning] 用 ifstream 读二进制数据时如何使用 read 和 gcount？**

二进制读取用非格式化的 read(buf, n)：不解析、不跳空白，试图精确提取 n 字节。文件剩余数据不足 n 字节时置 eofbit 加 failbit，实际读到的字节数用 gcount() 查询。

1. **不足即失败的语义（实测）：**对 12 字节文件执行 read(buf, 100)：gcount() 为 12，rdstate 为 failbit 加 eofbit。"read 失败"包含"没读够"，按块读取时最后一块必然触发，属正常路径而不是错误。
2. **块循环模式：**

    ```cpp
    char buf[4096];
    while (file.read(buf, sizeof buf) || file.gcount() > 0) {
        Process(buf, file.gcount());    // 本轮实际处理的字节数
    }
    ```

    read 读满块大小则条件为真。最后一块不足时 read 置失败位但 gcount() 大于 0，条件同样为真。数据耗尽后 gcount() 为 0，循环退出。

3. **struct 整块读写的边界：**read 和 write 搬运的是内存映像：包含填充字节，使用本机字节序，跨平台或跨编译器读取前必须确认两端布局一致。成员含指针或 std::string 的结构体不能这样落盘，存下的只是地址和指针结构而不是数据。
4. **配合 binary 模式：**二进制内容里出现 0x0A 等字节时，Windows 文本模式的行尾转换会破坏数据，二进制读写应始终以 binary 模式打开。Linux 上两种模式无差别。

**Q10: [learning] seekg 和 tellg 如何实现随机读取，有哪些状态与平台限制？**

tellg() 返回当前读取位置，seekg() 把读取位置移到指定处，两者配合即可随机访问。使用上的限制集中在状态交互、模式语义和越界行为。

1. **两种定位写法：**seekg(off, dir) 以 beg、cur、end 之一为基准做绝对或相对偏移。seekg(pos) 直接回到 tellg() 存下的位置，适合"记住位置稍后回来"的书签用法。
2. **状态限制（实测）：**读到文件尾后 rdstate 为 failbit 加 eofbit。seekg(0) 清掉 eofbit 但 failbit 仍在，重读依旧失败。clear() 加 seekg(0) 才能从头再读。C++11 起 seekg 保证清除 eofbit，更早版本不保证。
3. **文本模式的偏移语义：**标准只保证 tellg() 的返回值可以回传给 seekg()，不保证它是字节偏移——Windows 文本模式下行尾被转换，逻辑位置与物理字节错位，要按字节计算必须用 binary 模式。Linux/POSIX 无行尾转换，tellg() 就是字节偏移，seekg(0, end) 加 tellg() 是取文件大小的常用组合。
4. **越过文件尾：**把读取位置定位到文件尾之外不报错，但之后读取无数据可提取而失败。读方向不会扩展文件。

**Q11: [learning] 如何把整个文件读进一个 string？**

整文件读入 string 常用三种方法，按是否已知文件大小选择。已知大小且是二进制模式就用 seekg 取大小再一次 read，其余场景优先 ostringstream 接 file.rdbuf()，需要与泛型算法组合时用 istreambuf_iterator。

1. **seek 取大小加 read（实测）：**binary 模式下 seekg(0, end) 后 tellg() 即文件字节数，据此 resize 后 seekg(0) 再 read 可一次读全，恰好读完时不置任何状态位（实测 fail 为 0）。这一步必须在 binary 模式做——Windows 文本模式下 tellg() 不是字节数，取出的 size 与实际内容可能不一致。Linux 上两种模式一致。

    ```cpp
    std::ifstream file("data.bin", std::ios::binary);
    file.seekg(0, std::ios::end);
    std::string data;
    data.resize(file.tellg());
    file.seekg(0);
    file.read(&data[0], data.size());
    ```

2. **rdbuf() 借流缓冲拷贝（实测）：**ostringstream 接收 file.rdbuf() 后取 str()，实测文件全部内容一次到位。不需要预知大小，缓冲到缓冲直接搬运，代价是中间多出一个 ostringstream 对象。

    ```cpp
    std::ifstream src("data.bin");
    std::ostringstream ss;
    ss << src.rdbuf();
    std::string text = ss.str();
    ```

3. **istreambuf_iterator 构造（实测）：**std::string content((std::istreambuf_iterator<char>(file)), {}) 从当前读取位置逐字符走到文件尾，可与标准算法直接组合，代价是逐字符搬运最慢。

**Q12: [learning] ifstream 什么时候关闭文件，什么时候必须显式调用 close()？**

ifstream 析构时自动关闭文件，正常使用不需要显式 close()。必须显式调用的场景集中在复用流对象、尽早释放句柄和写方向落盘确认。

1. **RAII 行为：**fstream 家族的析构函数调用 close()，作用域结束或异常展开时文件句柄都会释放，不存在 fopen 漏 fclose 式的泄漏。实测 close() 之后 is_open() 为 0。
2. **复用对象必须先 close：**一个流同时只关联一个文件，不 close 直接 open 另一个文件会失败。换文件要么先 close 再 open，要么构造新的流对象。
3. **关闭后的读取必然失败（实测）：**close() 之后即使 clear() 清掉状态位，读取仍置 failbit——状态可以清，文件关联清不掉。
4. **写方向的落盘检查：**ofstream 的缓冲在 close 或析构时最后一次写出，close 可能因磁盘满等原因失败并置 failbit。关键数据落盘应显式 close() 并检查状态，ifstream 只读，不涉及这一步。

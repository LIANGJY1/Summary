// Q5: 预处理指令，将 iostream 头文件内容包含进来
#include <iostream>

// 演示 Q11: C 语言的头文件在 C++ 中的演变
#include <cstdio> // 对应 C 语言的 <stdio.h>

// Q10: 推荐做法，精确引入需要的名字，避免命名空间污染
using std::cout;
using std::endl;
using std::cin;

int customFunction();

// Q12: 标准的 main 函数签名
int main() {
    int *ptr = nullptr; // Q15: 初始化指针为 nullptr，避免悬空指针

    // Q14: 调用自定义函数
    customFunction();

    // Q16: cout 是标准输出流对象
    // Q17: << 是插入运算符，endl 插入换行并刷新缓冲区
    cout << "===== C++ 核心概念实操演示 =====" << endl;

    cout << "size of int: " << sizeof(void*) << " bytes" << endl;


    // 演示混合使用 C 风格的输出
    std::printf("Hello from C-style printf!\n");

    cout << "\n1. 命名空间演示完成。" << endl;
    cout << "2. 标准 I/O 流演示完成。" << endl;

    cout << "\n按下回车键退出程序..." << endl;
    
    // Q18: cin.get() 用于读取下一个字符，这里用来暂停程序，等待用户输入回车
    cin.get();

    // Q13: main 函数的末尾可以省略 return 0; 编译器会隐式添加。
    // 但为了代码清晰，显式写出是一个好习惯。
    return 0;
}

int customFunction()
{
    cout << "Custom function called!" << endl;
    return 0;
}

cmake_minimum_required(VERSION 3.22.1)
project(agentboxpty)

add_library(agentboxpty SHARED agentbox_pty.c)

find_library(log-lib log)
target_link_libraries(agentboxpty ${log-lib})

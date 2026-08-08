put, get, update, remove files.
the api: only given namespace and key(can be combined, so the api just ask for format and accepts arguments follows that format). There should have manager 
the system should check: fits the requirements(like file type, file size) duplicate, strategy for these case.
it should have "achieve" function, to compress files that don't access than a threshold. 
for files that access really frequently, use LFU to maintain it, than build cache for these files(cache to location of these files).
provide range, condition query and other operation for files.
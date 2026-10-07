def runs = new File(basedir, 'target/runs.txt').text.tokenize(',')
assert runs == ['ran', 'ran'] : "codegen ran ${runs.size()} times ${runs}, expected 2: first run and forced run, but not the unchanged second run"

def checksum = new File(basedir, 'target/inputs.sha256').text
assert checksum ==~ /[0-9a-f]{64}\n/ : "unexpected checksum file content '${checksum}'"

return true

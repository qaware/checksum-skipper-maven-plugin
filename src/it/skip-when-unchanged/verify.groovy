def decisions = new File(basedir, 'target/decisions.txt').text.tokenize(',')
assert decisions == ['false', 'true', 'false'] : "codegen saw codegen.skip=${decisions}, expected first run, unchanged, forced"

def checksum = new File(basedir, 'target/inputs.sha256').text
assert checksum ==~ /[0-9a-f]{64}\n/ : "unexpected checksum file content '${checksum}'"

return true

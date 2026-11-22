extends RefCounted
const RATE: int=22050
const IDS: Array[String]=["impact","hurt","jump","dash","land","shot","ground_hammer"]
const GAP_MS: Dictionary={"impact":40,"hurt":80,"jump":60,"dash":60,"land":60,"shot":50,"ground_hammer":95}
const PRIORITY: Dictionary={"impact":2,"hurt":3,"jump":1,"dash":1,"land":0,"shot":1,"ground_hammer":2}
const SHAPES: Dictionary={
	"impact":[0.080,950.0,310.0,0.45],
	"hurt":[0.135,240.0,95.0,0.32],
	"jump":[0.095,420.0,760.0,0.03],
	"dash":[0.075,760.0,260.0,0.80],
	"land":[0.065,140.0,65.0,0.30],
	"shot":[0.090,1100.0,460.0,0.18],
	"ground_hammer":[0.144,126.0,48.0,0.32]}
# Original deterministic mono signed-16 little-endian PCM; generated once by the service.
static func pcm(id: String) -> PackedByteArray:
	if id not in IDS:return PackedByteArray()
	var shape: Array=SHAPES[id]
	var count: int=ceili(float(shape[0])*RATE)
	var data:=PackedByteArray();data.resize(count*2)
	var phase: float=0.0
	var seed: int=12345+IDS.find(id)*7919
	for index in range(count):
		var fraction: float=float(index)/float(count-1)
		var frequency: float=lerpf(float(shape[1]),float(shape[2]),fraction)
		phase=fmod(phase+TAU*frequency/RATE,TAU)
		seed=(seed*1664525+1013904223)&0x7fffffff
		var noise: float=float(seed)/1073741824.0-1.0
		var envelope: float=minf(1.0,float(index)/(RATE*0.004))*pow(1.0-fraction,2.0)
		var sample: int=roundi(32767.0*0.24*envelope*lerpf(sin(phase),noise,float(shape[3])))
		data[index*2]=sample&255;data[index*2+1]=(sample>>8)&255
	return data

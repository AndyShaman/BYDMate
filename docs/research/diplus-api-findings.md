# D+ (DiPlus) API — Complete Research Findings

**Date:** 2026-04-09
**Source:** Decompiled `diplus.1.3.8-beta16.apk` (jadx --show-bad-code)
**Verified on device:** getDiPars (50+ params), sendCmd (一键通风, 一键关窗)

---

## 1. HTTP API Endpoints (port 8988)

| Endpoint | Method | Parameter | Description |
|---|---|---|---|
| `/api/getDiPars` | GET/POST | `text` | Read car parameters (template format) |
| `/api/getVal` | GET/POST | `name` | Read single parameter value |
| `/api/sendCmd` | GET/POST | `cmd` | **Send control command** |
| `/api/setXD` | POST | body | Set configuration |
| `/api/setTrigger` | POST | JSON body | Set automation trigger |
| `/api/setTriggerV2` | POST | JSON body | Set automation trigger v2 |
| `/api/videoDirs` | GET | `type`, `startTime`, `endTime` | List dashcam video directories |
| `/api/videoFiles` | GET | `type`, `dir` | List dashcam video files |
| `/api/videoInfo` | GET | `key` | Get video thumbnail |
| `/api/videoStream` | GET | `key` | Stream dashcam video |

All endpoints require `auth` parameter (can be empty string).
Chinese characters in query params MUST be URL-encoded.

### getDiPars format
```
GET /api/getDiPars?text=SOC:{电量百分比}|Speed:{车速}|Gear:{档位}
Response: {"success":true,"val":"SOC:82|Speed:0|Gear:1"}
```

### sendCmd format
```
GET /api/sendCmd?cmd=迪加车窗全开
Response: {"success":true}
```

**CRITICAL:** Extended commands require `迪加` prefix. Without it, only macro commands work (一键关窗, 一键通风, etc.).
**WARNING:** sendCmd returns `{"success":true}` even for unrecognized commands.

---

## 2. Complete Command Catalog

All commands below must be prefixed with `迪加` when sent via `/api/sendCmd`.
Exception: commands in section 2.1 (macro commands) work without prefix.

### 2.1 Macro Commands (no prefix needed)

| Command | Description |
|---|---|
| `一键关窗` | Close all windows |
| `一键通风` | Ventilate all windows (~12%) |
| `长途模式` / `高速模式` | Highway driving mode |
| `城市模式` | City driving mode |
| `一键舒睡` / `睡觉模式` | Sleep mode |
| `屏幕关闭` | Screen off |
| `开启哨兵模式` / `打开哨兵模式` | Sentry mode ON |
| `关闭哨兵模式` | Sentry mode OFF |
| `开启自动启动哨兵模式` | Auto-sentry ON |
| `关闭自动启动哨兵模式` | Auto-sentry OFF |

### 2.2 Window Control (prefix `迪加` required)

| Command | Description |
|---|---|
| `车窗关闭` | Close all windows |
| `车窗全开` | **Open all windows FULLY** |
| `车窗半开` | Open all windows 50% |
| `前排车窗全开` | Open front windows fully |
| `前排车窗关闭` | Close front windows |
| `前排车窗半开` | Open front windows 50% |
| `前排车窗通风` | Front windows ventilation |
| `后排车窗全开` | Open rear windows fully |
| `后排车窗关闭` | Close rear windows |
| `后排车窗半开` | Open rear windows 50% |
| `后排车窗通风` | Rear windows ventilation |
| `车窗通风` | All windows ventilation |

#### Individual Window Percentage (type 59)

Format: `迪加{subject}打开{N}` or `迪加{subject}打开百分之{N}%`

| Subject keywords | Window |
|---|---|
| `主驾` / `左前` / `前左` | Driver (FL) |
| `副驾` / `右前` / `前右` | Passenger (FR) |
| `后左` / `左后` | Rear left (RL) |
| `后右` / `右后` | Rear right (RR) |
| `天窗` | Sunroof |
| `遮阳帘` | Sunshade |

Examples:
```
迪加主驾打开100      → Driver window 100% open
迪加左后打开50       → Rear left window 50%
迪加天窗打开30       → Sunroof 30%
迪加遮阳帘打开0      → Close sunshade
```

### 2.3 Climate Control

| Command | Description |
|---|---|
| `关空调` | AC OFF |
| `空调升温` | AC temperature +1 |
| `空调降温` | AC temperature -1 |
| `设置温度{N}` | Set AC to N°C (1-49) |
| `升风速` | Fan speed +1 |
| `降风速` | Fan speed -1 |
| `设置风速{N}` | Set fan speed to N (1-49) |
| `空调自动` / `自动空调` | AC auto mode |
| `内循环` / `空调内循环` | Recirculation ON |
| `外循环` / `空调外循环` | Recirculation OFF |
| `打开空调通风` / `空调通风` | AC ventilation mode |
| `关闭空调通风` | AC ventilation OFF |
| `吹前挡` | Windshield defrost ON |
| `关闭吹前挡` | Windshield defrost OFF |

#### AC Blow Direction

| Command | Description |
|---|---|
| `空调吹面` | Face |
| `空调吹面吹脚` | Face + Feet |
| `空调吹脚` | Feet |
| `空调吹脚除霜` | Feet + Defrost |
| `空调除霜` | Defrost |
| `空调吹面吹脚除霜` | Face + Feet + Defrost |
| `空调吹面除霜` | Face + Defrost |

### 2.4 Seat Control

#### Heating (2 levels)

| Command | Description |
|---|---|
| `主驾座椅加热1档` | Driver seat heat level 1 |
| `主驾座椅加热2档` | Driver seat heat level 2 |
| `主驾座椅加热关闭` / `关闭主驾座椅加热` | Driver seat heat OFF |
| `副驾座椅加热1档` | Passenger seat heat level 1 |
| `副驾座椅加热2档` | Passenger seat heat level 2 |
| `副驾座椅加热关闭` / `关闭副驾座椅加热` | Passenger seat heat OFF |

#### Ventilation (2 levels)

| Command | Description |
|---|---|
| `主驾座椅通风1档` | Driver seat vent level 1 |
| `主驾座椅通风2档` | Driver seat vent level 2 |
| `主驾座椅通风关闭` / `关闭主驾座椅通风` | Driver seat vent OFF |
| `副驾座椅通风1档` | Passenger seat vent level 1 |
| `副驾座椅通风2档` | Passenger seat vent level 2 |
| `副驾座椅通风关闭` / `关闭副驾座椅通风` | Passenger seat vent OFF |

#### Massage (3 levels, 5 modes)

| Command | Description |
|---|---|
| `主驾按摩1档` / `2档` / `3档` | Driver massage level 1/2/3 |
| `关闭主驾按摩` | Driver massage OFF |
| `主驾按摩模式1` ... `模式5` | Driver massage mode 1-5 |
| `副驾按摩1档` / `2档` / `3档` | Passenger massage level 1/2/3 |
| `关闭副驾按摩` | Passenger massage OFF |
| `副驾按摩模式1` ... `模式5` | Passenger massage mode 1-5 |

### 2.5 Steering Wheel

| Command | Description |
|---|---|
| `开启方向盘加热` / `方向盘加热` | Steering wheel heat ON |
| `关闭方向盘加热` | Steering wheel heat OFF |

### 2.6 Lights

| Command | Description |
|---|---|
| `打开日行灯` / `日行灯打开` | DRL ON |
| `关闭日行灯` / `日行灯关闭` | DRL OFF |
| `打开氛围灯` / `氛围灯打开` | Ambient light ON |
| `关闭氛围灯` / `氛围灯关闭` | Ambient light OFF |
| `打开车内灯` | Interior light ON |
| `关闭车内灯` | Interior light OFF |
| `双闪` / `开双闪` | Hazard lights ON |
| `关双闪` / `关闭闪灯` | Hazard lights OFF |
| `左闪灯` / `闪左灯` | Left blinker |
| `右闪灯` / `闪右灯` | Right blinker |
| `打开雾灯` / `开雾灯` | Fog lights ON |
| `关闭雾灯` / `关雾灯` | Fog lights OFF |
| `闪灯` | Flash headlights |
| `闪灯鸣笛` | Flash + honk |

### 2.7 Door & Lock Control

| Command | Description |
|---|---|
| `车门解锁` | Unlock doors |
| `车门上锁` | Lock doors |
| `锁车` | Lock car |
| `解锁` | Unlock car |
| `打开左童锁` | Left child lock ON |
| `关闭左童锁` | Left child lock OFF |
| `打开右童锁` | Right child lock ON |
| `关闭右童锁` | Right child lock OFF |
| `开后备箱` | Open trunk |
| `关后备箱` | Close trunk |

### 2.8 Sunroof & Sunshade

| Command | Description |
|---|---|
| `打开遮阳帘` / `遮阳帘打开` | Sunshade OPEN |
| `关闭遮阳帘` / `遮阳帘关闭` | Sunshade CLOSE |

(See also: individual % control in section 2.2)

### 2.9 Drive Modes

| Command | Description |
|---|---|
| `切换HEV` | HEV mode |
| `切换EV` | EV mode |
| `切换强制EV` | Forced EV mode |
| `ECO模式` | ECO driving mode |
| `SPORT模式` | SPORT driving mode |
| `NORMAL模式` | NORMAL driving mode |
| `雪地模式` | SNOW driving mode |
| `智能保电` | Smart battery conservation |
| `强制保电` | Forced battery conservation |

### 2.10 Suspension (云辇 DiSus)

| Command | Description |
|---|---|
| `云辇舒适` | Comfort suspension |
| `云辇运动` | Sport suspension |
| `云辇舒适偏弱` | Comfort - soft |
| `云辇舒适适中` | Comfort - medium |
| `云辇舒适较强` | Comfort - firm |

### 2.11 Energy & Braking

| Command | Description |
|---|---|
| `能量回馈标准` | Standard regen braking |
| `能量回馈较大` | Strong regen braking |
| `转向助力舒适` | Comfort steering |
| `转向助力运动` | Sport steering |
| `打开主动刹车` | AEB ON |
| `关闭主动刹车` | AEB OFF |
| `制动助力标准` | Standard brake assist |
| `制动助力舒适` | Comfort brake assist |

### 2.12 Mirrors & HUD

| Command | Description |
|---|---|
| `打开HUD` | HUD ON |
| `关闭HUD` | HUD OFF |
| `打开自动远光` | Auto high-beam ON |
| `关闭自动远光` | Auto high-beam OFF |
| `后视镜加热` | Mirror heating ON |
| `关闭后视镜加热` | Mirror heating OFF |
| `后视镜锁车折叠` | Fold mirrors on lock |
| `后视镜锁车展开` | Unfold mirrors on lock |
| `大灯高度{N}` | Headlight height level N |

### 2.13 Audio & Volume

| Command | Description |
|---|---|
| `设置音量{N}` | Set media volume (0-100) |
| `加音量` / `增加音量` | Volume up |
| `降音量` / `减音量` | Volume down |
| `设置导航音量{N}` | Set navigation volume (0-10) |
| `设置车外音量{N}` | Set external speaker volume (0-100) |
| `切静音` | Mute |
| `暂停音乐` / `暂停播放` | Pause music |
| `恢复音乐` / `恢复播放` | Resume music |
| `下一曲` | Next track |
| `上一曲` | Previous track |

### 2.14 Other Controls

| Command | Description |
|---|---|
| `打开引擎模拟音` | Engine sound ON |
| `关闭引擎模拟音` | Engine sound OFF |
| `打开蓝牙` | Bluetooth ON |
| `关闭蓝牙` | Bluetooth OFF |
| `深色模式` | Dark theme |
| `浅色模式` | Light theme |
| `返回桌面` | Go to home screen |
| `下电` | Power OFF |
| `唤醒休眠` | Wake from sleep |
| `设置SOC{N}` | Set charge target to N% |
| `预约充电HH:MM-HH` | Schedule charging |
| `发送CAN[payload]` | Raw CAN frame (advanced) |

### 2.15 Sentry & Recording

| Command | Description |
|---|---|
| `打开熄火录制` | Post-shutdown recording ON |
| `关闭熄火录制` | Post-shutdown recording OFF |
| `打开熄火哨兵` | Sentry recording ON |
| `打开缩时哨兵` | Timelapse sentry ON |
| `打开全景记录仪` | Panoramic dashcam ON |
| `关闭全景记录仪` | Panoramic dashcam OFF |

---

## 3. Complete Parameter Catalog (getDiPars)

### 3.1 Verified on Device (2026-04-09)

| Alias | Chinese Name | ID | Type | Sample Value |
|---|---|---|---|---|
| SOC | 电量百分比 | 33 | double | 82 (%) |
| Speed | 车速 | 2 | double | 0 (km/h) |
| Gear | 档位 | 4 | enum | 1=P, 2=R, 3=N, 4=D |
| PowerState | 电源状态 | 1 | enum | 0=OFF, 1=ON, 2=DRIVE |
| ACStatus | 空调状态 | 77 | enum | 0=OFF, 1=ON |
| ACTemp | 主驾驶空调温度 | 27 | int | 24 (°C) |
| FanLevel | 风量档位 | 78 | string | 7 |
| InsideTemp | 车内温度 | 25 | int | 9 (°C) |
| ExtTemp | 车外温度 | 26 | int | 1 (°C) |
| ACCirc | 空调循环方式 | 79 | enum | 0=外循环, 1=内循环 |
| ACMode | 空调出风模式 | 80 | enum | 1-7 (see sec 2.3) |
| DoorFL | 主驾车门 | 81 | enum | 0=closed, 1=open |
| DoorFR | 副驾车门 | 82 | enum | 0/1 |
| DoorRL | 左后车门 | 83 | enum | 0/1 |
| DoorRR | 右后车门 | 84 | enum | 0/1 |
| WindowFL | 主驾车窗打开百分比 | 61 | int | 0-100 (%) |
| WindowFR | 副驾车窗打开百分比 | 62 | int | 0-100 |
| WindowRL | 左后车窗打开百分比 | 63 | int | 0-100 |
| WindowRR | 右后车窗打开百分比 | 64 | int | 0-100 |
| Sunroof | 天窗打开百分比 | 65 | int | 0-100 |
| SunShade | 遮阳帘打开百分比 | 66 | int | 0-100 |
| Trunk | 后备箱门 | 86 | enum | 0/1 |
| Hood | 引擎盖 | 85 | enum | 0/1 |
| FuelCap | 油箱盖 | 87 | enum | 0/1 |
| TirePressFL | 左前轮气压 | 53 | int | 230 (kPa) |
| TirePressFR | 右前轮气压 | 54 | int | 230 |
| TirePressRL | 左后轮气压 | 55 | int | 227 |
| TirePressRR | 右后轮气压 | 56 | int | 230 |
| LightLow | 近光灯 | 100 | enum | 0=OFF, 1=ON |
| LightHigh | 远光灯 | 101 | enum | 0/1 |
| DRL | 日行灯 | 107 | enum | 0=无效, 1=ON, 2=OFF |
| Hazards | 双闪 | 109 | enum | 0=无效, 1=OFF, 2=ON |
| TurnL | 左转向灯 | 57 | enum | 0=OFF, 1=ON |
| TurnR | 右转向灯 | 58 | enum | 0/1 |
| DriveMode | 整车运行模式 | 68 | enum | 1=ECO, 2=SPORT |
| WorkMode | 整车工作模式 | 67 | enum | 0=停止, 1=EV, 2=强制EV, 3=HEV |
| SeatbeltFL | 主驾驶安全带状态 | 21 | enum | 0=未系, 1=已系, 2=无效 |
| LockFL | 主驾车门锁 | 59 | enum | 1=解锁, 2=锁定 |
| AutoPark | 自动驻车 | 88 | enum | 0=禁用, 1=待激活, 2=激活 |
| ACCStatus | ACC巡航状态 | 89 | enum | 0-5 |
| BrakeDepth | 刹车深度 | 6 | string | 0 |
| AccelDepth | 加速踏板深度 | 7 | string | 0 |
| SteeringAngle | 方向盘转角 | 30 | double | -73.9 (°) |
| Slope | 坡度 | 110 | int | 0 |
| Rain | 雨量 | 111 | int | 0 |
| FrontCarDist | 前车距离 | 51 | int | 0 (m) |
| WiperSpeed | 前雨刮速度 | 48 | int | 1 |

### 3.2 Full Parameter List from Decompilation (115+)

| ID | Chinese Name | English | Type |
|---|---|---|---|
| 1 | 电源状态 | Power state | enum: 关/上电/行车 |
| 2 | 车速 | Speed | double (km/h) |
| 3 | 里程 | Mileage | int (÷10) |
| 4 | 档位 | Gear | enum: P/R/N/D/M/S |
| 5 | 发动机转速 | Engine RPM | int |
| 6 | 刹车深度 | Brake depth | string |
| 7 | 加速踏板深度 | Accelerator depth | string |
| 8 | 前电机转速 | Front motor RPM | string |
| 9 | 后电机转速 | Rear motor RPM | int |
| 10 | 发动机功率 | Engine power | int |
| 11 | 前电机扭矩 | Front motor torque | double |
| 12 | 充电枪插枪状态 | Charge gun | enum: 断开/交流枪/直流枪/转换枪/放电枪 |
| 13 | 百公里电耗 | kWh/100km | double |
| 14 | 最高电池温度 | Max battery temp | double (°C) |
| 15 | 平均电池温度 | Avg battery temp | double |
| 16 | 最低电池温度 | Min battery temp | double |
| 17 | 最高电池电压 | Max cell voltage | double (V) |
| 18 | 最低电池电压 | Min cell voltage | double |
| 19 | 上次雨刮时间 | Last wiper time | timestamp |
| 20 | 天气 | Weather | enum: 晴天/雨天 |
| 21 | 主驾驶安全带状态 | Driver seatbelt | enum: 未系/已系/无效 |
| 22 | 远程锁车状态 | Remote lock | enum: 未锁/锁定 |
| 25 | 车内温度 | Interior temp | int (°C) |
| 26 | 车外温度 | Exterior temp | int (°C) |
| 27 | 主驾驶空调温度 | Driver AC temp | int (°C) |
| 28 | 温度单位 | Temp unit | enum: 华氏度/摄氏度 |
| 29 | 电池容量 | Battery capacity | double (kWh) |
| 30 | 方向盘转角 | Steering angle | double (°) |
| 31 | 方向盘转速 | Steering speed | int |
| 32 | 总电耗 | Total consumption | double (kWh) |
| 33 | 电量百分比 | SOC | double (%) |
| 34 | 油量百分比 | Fuel % | int |
| 35 | 总燃油消耗 | Total fuel | double |
| 36 | 车道线曲率 | Lane curvature | double |
| 37 | 右侧线距离 | Right lane distance | double |
| 38 | 左侧线距离 | Left lane distance | double |
| 39 | 蓄电池电压 | 12V battery voltage | double (V) |
| 40-47 | 雷达* | Radar sensors (8) | int (cm) |
| 48 | 前雨刮速度 | Front wiper speed | int |
| 49 | 雨刮档位 | Wiper mode | int |
| 50 | 巡航开关 | Cruise switch | int |
| 51 | 前车距离 | Front car distance | int (m) |
| 52 | 充电状态 | Charge status | enum: 无效/Ready/开始/完成/终止 |
| 53-56 | *轮气压 | Tire pressures (4) | int (kPa) |
| 57 | 左转向灯 | Left turn signal | enum: 关闭/开启 |
| 58 | 右转向灯 | Right turn signal | enum |
| 59 | 主驾车门锁 | Driver door lock | enum: 解锁/锁定 |
| 61-64 | *车窗打开百分比 | Window positions (4) | int (0-100%) |
| 65 | 天窗打开百分比 | Sunroof position | int (0-100%) |
| 66 | 遮阳帘打开百分比 | Sunshade position | int (0-100%) |
| 67 | 整车工作模式 | Vehicle work mode | enum: 停止/EV/强制EV/HEV |
| 68 | 整车运行模式 | Drive mode | enum: ECO/SPORT |
| 69-72 | 月/日/时/分 | Date/Time | int |
| 73 | 副驾安全带警告 | Passenger seatbelt warn | enum |
| 74-76 | 二排*安全带 | Rear seatbelts (3) | enum |
| 77 | 空调状态 | AC status | enum: 关闭/开启 |
| 78 | 风量档位 | Fan level | string |
| 79 | 空调循环方式 | AC circulation | enum: 外循环/内循环 |
| 80 | 空调出风模式 | AC blow mode | enum (7 modes) |
| 81-84 | *车门 | Doors (4) | enum: 关闭/打开 |
| 85 | 引擎盖 | Hood | enum |
| 86 | 后备箱门 | Trunk | enum |
| 87 | 油箱盖 | Fuel cap | enum |
| 88 | 自动驻车 | Auto park | enum (4 states) |
| 89 | ACC巡航状态 | ACC cruise | enum (6 states) |
| 90-91 | *后接近告警 | Rear approach warn (2) | enum |
| 92 | 车道保持状态 | Lane keeping | enum (5 states) |
| 93-96 | *车门锁 | Door locks (4) | enum |
| 97-98 | *儿童锁 | Child locks (2) | enum |
| 99 | 小灯 | Position lights | enum |
| 100 | 近光灯 | Low beam | enum |
| 101 | 远光灯 | High beam | enum |
| 104 | 前雾灯 | Front fog | enum |
| 105 | 后雾灯 | Rear fog | enum |
| 106 | 脚照灯 | Foot light | enum |
| 107 | 日行灯 | DRL | enum |
| 108 | 发动机水温 | Engine water temp | int (°C) |
| 109 | 双闪 | Hazards | enum |
| 110 | 坡度 | Slope | int (°) |
| 111 | 雨量 | Rainfall | int |
| 112 | 副驾安全带 | Passenger seatbelt | enum |
| 113 | 秒 | Seconds | int |
| 114 | SOC | SOC | int (%) |
| 115 | 转向信号 | Turn signal | enum (10 values) |
| 1001-1018 | D+ internal | Screen, sentry, WiFi, BT, etc. | various |

---

## 4. D+ Built-in Trigger System

D+ has its own automation engine accessible via `/api/setTrigger` and `/api/setTriggerV2`.

### Condition Model
```json
{
  "logicalOperator": "AND",     // AND or OR
  "list": [
    {
      "leftId": 2,              // Parameter ID (e.g., 2 = Speed)
      "operator": "GREATER_THAN", // >, >=, <, <=, EQUAL, NOT_EQUAL, ANY
      "rightVal": 60.0
    }
  ]
}
```

### TriggerConfItem
```json
{
  "did": 2,                     // Parameter ID to watch for changes
  "enable": true,
  "delay": 0,                   // Delay ms before executing
  "min": 60000,                 // Min interval ms between executions
  "sm": true,                   // Trigger once on condition change
  "condition": { ... },         // Condition object
  "onTrue": "迪加车窗关闭",      // Command when condition is true
  "onFalse": ""                 // Command when condition is false
}
```

### Operators
| Operator | Symbol | Description |
|---|---|---|
| GREATER_THAN | `>` | Greater than |
| GREATER_THAN_OR_EQUAL | `>=` | Greater or equal |
| LESS_THAN | `<` | Less than |
| LESS_THAN_OR_EQUAL | `<=` | Less or equal |
| EQUAL | `是` | Equals |
| NOT_EQUAL | `不是` | Not equals |
| ANY | `发生变化` | Value changed |

---

## 5. CAN Layer Architecture

D+ does NOT directly send CAN frames. It uses BYD's Android HAL:

```
sendCmd text → f.a() parser → command type → MainService handler
  → BYDAutoBodyworkDevice (windows, doors, trunk)
  → BYDAutoAcDevice (climate, temperature)
  → BYDAutoLightDevice (lights, ambient)
  → BYDAutoFeatureIds (CAN frame IDs abstracted)
```

Raw CAN injection is possible via `发送CAN[payload]` but not needed for standard controls.

---

## 6. Recommendations for BYDMate Automation

### Architecture: Own Engine + sendCmd
- Poll getDiPars every 3 seconds (already in TrackingService)
- Evaluate rules against DiParsData
- Execute actions via `/api/sendCmd?cmd=迪加{command}`
- Log all executions in Room DB

### Why NOT use D+ setTrigger:
- D+ trigger format is complex and underdocumented
- We can't log/notify from D+ triggers
- Our 3-second polling is sufficient for comfort/convenience rules
- Full control over UI and rule management

### Safety Rules:
- Window/sunroof commands: ONLY when Gear == P (1) or Speed == 0
- Never send: 发送CAN, 执行SHELL, 下电
- Always check PowerState before any command
- Cooldown between repeated commands (min 60s)
- Log every command with timestamp, rule, result

import axios from 'axios'

export function getAlarmList() {
    return axios.get('http://localhost:8084/alarm/list')
}
export function getAlarmDetail(id) {
    return axios.get(`http://localhost:8084/alarm/detail/${id}`)
}
